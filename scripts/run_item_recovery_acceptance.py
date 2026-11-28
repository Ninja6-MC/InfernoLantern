"""Run three opt-in Paper item-drop recovery controls on disposable copies."""

import argparse
from contextlib import closing, contextmanager
from dataclasses import dataclass
import hashlib
from io import BytesIO
import json
from pathlib import Path
import queue
import re
import shutil
import socket
import sqlite3
import struct
import subprocess
import tempfile
import threading
import time
import uuid

REPO = Path(__file__).resolve().parents[1]
HELPER = REPO / 'src' / 'recoveryAcceptance'
PAPER_SHA = '4c734aeb4da98845934af1e5481f0df7c80ad3ca50d272319ffe2b0e8a187c8a'
RUNTIME_SHA = 'dadd84d1697efa8a29bcad6c81e503efdbaaa2dac85dbd4662c8243c97f0b8a4'
RUNTIME = Path('versions/26.3/paper-26.3.jar')
MOJANG = Path('cache/mojang_26.3.jar')
MOJANG_SHA = 'd052f14d7a173734fba553711e5b570162e2f2a313267ee31a21b975a679be64'
HOST = '127.0.0.1'
USERNAME = 'FixturePlayer'
PLAYER_ID = uuid.UUID(bytes=hashlib.md5(f'OfflinePlayer:{USERNAME}'.encode('ascii')).digest(), version=3)
FIXTURE = Path('fixture')
DATABASE = FIXTURE / 'recovery.db'
NATIVE = Path('plugins/InfernoLantern/native-runtime')
DIMENSION = Path('world/dimensions/minecraft/overworld')
WORLD_METADATA = Path('data/paper/metadata.dat')
ENTITY_REGION = Path('entities/r.-1.0.mca')
BOOT = ('cache', 'libraries', 'versions', 'paper.jar')
CASES = {
    'before': ('ROLLED_BACK', 'ROLLED_BACK', 'ABSENT'),
    'after': ('COMPLETED', 'COMPLETE', 'ACTIVE'),
    'mixed-saves': ('ADMIN_REQUIRED', 'INTENT', 'STAGED'),
}


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


@dataclass(frozen=True)
class Inputs:
    core: Path
    paper: Path
    bootstrap: Path
    java_home: Path
    scratch: Path

    def command(self, name):
        return str(self.java_home / 'bin' / name)


def verify_digest(path, expected):
    if not re.fullmatch(r'[a-f0-9]{64}', expected) or not path.is_file() or sha256(path) != expected:
        raise ValueError(f'Wrong artifact or SHA-256: {path.name}')


def parse_inputs(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ('core', 'paper', 'bootstrap', 'java-home', 'scratch'):
        parser.add_argument(f'--{flag}', required=True, type=Path)
    parser.add_argument('--core-sha256', required=True)
    args = parser.parse_args(argv)
    inputs = Inputs(*(path.resolve(strict=True) for path in
                      (args.core, args.paper, args.bootstrap, args.java_home, args.scratch)))
    verify_digest(inputs.core, args.core_sha256)
    verify_digest(inputs.paper, PAPER_SHA)
    verify_digest(inputs.bootstrap / RUNTIME, RUNTIME_SHA)
    verify_digest(inputs.bootstrap / MOJANG, MOJANG_SHA)
    for directory in ('cache', 'libraries', 'versions'):
        tree = inputs.bootstrap / directory
        if inputs.scratch.is_relative_to(tree):
            raise ValueError('Scratch must be outside copied bootstrap inputs')
        if not tree.is_dir() or tree.is_symlink() or any(path.is_symlink() for path in tree.rglob('*')):
            raise ValueError(f'Bootstrap needs local, non-aliased copies of {directory}')
    if not inputs.scratch.is_dir():
        raise ValueError('Scratch must be an existing directory')
    version = subprocess.run([inputs.command('java'), '-version'], text=True, capture_output=True, check=True)
    line = version.stderr.splitlines()[0]
    if not re.search(r'\bversion "25[."]', line) or not all(
            Path(inputs.command(tool)).is_file() for tool in ('javac', 'jar')):
        raise ValueError('A Java 25 JDK is required')
    print(line, flush=True)
    return inputs


def build_fixture(inputs, root):
    libraries = sorted((inputs.bootstrap / 'libraries').rglob('*.jar'))
    classpath = ':'.join(str(path) for path in (*libraries, inputs.bootstrap / RUNTIME, inputs.core))
    sources = sorted((HELPER / 'java').rglob('*.java'))
    classes = root / 'classes'
    subprocess.run([inputs.command('javac'), '--release', '25', '-encoding', 'UTF-8', '-classpath',
                    classpath, '-d', str(classes), *(str(path) for path in sources)], check=True)
    jar = root / 'InfernoLanternItemDropAcceptance.jar'
    subprocess.run([inputs.command('jar'), 'cf', str(jar), '-C', str(classes), '.',
                    '-C', str(HELPER / 'resources'), 'plugin.yml'], check=True)
    return jar


def varint(value):
    result = bytearray()
    while value > 127:
        result.append(128 | (value & 127))
        value >>= 7
    result.append(value)
    return bytes(result)


def protocol_string(value):
    data = value.encode('utf-8')
    return varint(len(data)) + data


def read_varint(read):
    result = 0
    for shift in range(0, 35, 7):
        data = read(1)
        if not data:
            raise EOFError
        byte = data[0]
        result |= (byte & 127) << shift
        if byte < 128:
            return result
    raise ValueError('Malformed fixture VarInt')


class Client:
    """Minimal offline Paper 26.3 fixture peer. It only connects to the loopback server."""

    def __init__(self, port):
        self.socket = socket.create_connection((HOST, port), timeout=50)
        self.reader = self.socket.makefile('rb')
        try:
            self.send(0, varint(777) + protocol_string(HOST) + struct.pack('>H', port) + varint(2))
            self.send(0, protocol_string(USERNAME) + PLAYER_ID.bytes)
            if self.receive()[0] != 2:
                raise RuntimeError('Fixture login failed')
            self.send(3)
            self.send(0, protocol_string('en_us') + bytes([3, 0, 1, 127, 1, 0, 0, 0]))
            while True:
                packet, payload = self.receive()
                if packet == 15:
                    self.send(7, varint(0))
                elif packet == 3:
                    self.send(3)
                    break
                elif packet in (4, 5):
                    self.send(packet, payload)
                elif packet == 2:
                    raise RuntimeError('Fixture configuration failed')
        except BaseException:
            self.reader.close()
            self.socket.close()
            raise
        self.thread = threading.Thread(target=self.drain, daemon=True)
        self.thread.start()

    def send(self, packet, payload=b''):
        data = varint(packet) + payload
        self.socket.sendall(varint(len(data)) + data)

    def receive(self):
        length = read_varint(self.reader.read)
        if not 0 < length <= 2 * 1024 * 1024:
            raise ValueError('Fixture packet outside size limit')
        data = self.reader.read(length)
        if len(data) != length:
            raise EOFError
        stream = BytesIO(data)
        return read_varint(stream.read), stream.read()

    def drain(self):
        try:
            while True:
                packet, payload = self.receive()
                if packet == 45:
                    self.send(28, payload)
                elif packet == 73:
                    stream = BytesIO(payload)
                    teleport = read_varint(stream.read)
                    move = stream.read()
                    self.send(0, varint(teleport) + move[:24] + move[48:56])
        except (EOFError, OSError, ValueError):
            pass

    def close(self):
        try:
            self.socket.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.thread.join(timeout=3)
        self.reader.close()
        self.socket.close()


class Paper:
    def __init__(self, inputs, directory, port):
        self.port = port
        self.log = (directory / 'paper.log').open('w', encoding='utf-8')
        temp = directory / 'java-temp'
        temp.mkdir(mode=0o700)
        self.process = subprocess.Popen([inputs.command('java'), f'-Djava.io.tmpdir={temp}',
            '-XX:ActiveProcessorCount=4', '-Xms256m', '-Xmx1200m', '-jar', 'paper.jar', 'nogui'],
            cwd=directory, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding='utf-8', errors='replace')
        self.client = None
        self.lines = queue.Queue()
        self.thread = threading.Thread(target=self.capture_log)
        self.thread.start()

    def capture_log(self):
        for line in self.process.stdout:
            self.log.write(line)
            self.log.flush()
            self.lines.put(line)
        self.lines.put('')

    def expect(self, phrase, timeout=25):
        expires = time.monotonic() + timeout
        while (remaining := expires - time.monotonic()) > 0:
            try:
                line = self.lines.get(timeout=remaining)
            except queue.Empty:
                break
            if phrase in line:
                return line.split(phrase, 1)[1].strip()
            if not line or 'CRASH-ERROR' in line:
                break
        raise RuntimeError(f'Missing fixture response {phrase}; see {self.log.name}')

    def send(self, command):
        self.process.stdin.write(command + '\n')
        self.process.stdin.flush()

    def probe(self, arguments):
        self.send('crashprobe ' + arguments)
        self.expect('CRASH-ACK ' + arguments.split()[0])

    def ready(self):
        self.expect('For help, type', 85)
        self.client = Client(self.port)
        self.expect('logged in with entity id')

    def close(self, require_crash):
        try:
            if self.process.poll() is None:
                self.process.kill()
            if self.process.wait(timeout=25) != -9 and require_crash:
                raise RuntimeError('Fixture process exited without the intended crash cut')
        finally:
            if self.client is not None:
                self.client.close()
            self.thread.join(timeout=10)
            self.log.close()
            self.process.stdin.close()
            self.process.stdout.close()


@contextmanager
def boot(inputs, directory, port):
    server = Paper(inputs, directory, port)
    completed = False
    try:
        server.ready()
        yield server
        completed = True
    finally:
        server.close(require_crash=completed)


def prepare(inputs, directory, port, helper, source=None):
    directory.mkdir()
    if source is None:
        for dependency in ('cache', 'libraries', 'versions'):
            shutil.copytree(inputs.bootstrap / dependency, directory / dependency)
        shutil.copy2(inputs.paper, directory / 'paper.jar')
    else:
        for artifact in BOOT:
            (source / artifact).rename(directory / artifact)
        shutil.copytree(source / 'world', directory / 'world')
        shutil.copytree(source / NATIVE, directory / NATIVE)
    properties = {
        'server-ip': HOST, 'server-port': str(port), 'online-mode': 'false', 'enforce-secure-profile': 'false',
        'enable-query': 'false', 'enable-rcon': 'false', 'management-server-enabled': 'false',
        'network-compression-threshold': '-1', 'level-type': r'minecraft\:flat', 'allow-nether': 'false',
        'enable-code-of-conduct': 'false', 'white-list': 'true', 'enforce-whitelist': 'true',
        'generate-structures': 'false', 'spawn-protection': '0', 'simulation-distance': '3', 'view-distance': '3',
        'difficulty': 'peaceful', 'sync-chunk-writes': 'true', 'pause-when-empty-seconds': '-1',
    }
    (directory / 'server.properties').write_text(''.join(f'{key}={value}\n' for key, value in properties.items()),
                                                encoding='ascii')
    (directory / 'bukkit.yml').write_text('settings:\n  allow-end: false\nticks-per:\n  autosave: 0\n', encoding='ascii')
    (directory / 'eula.txt').write_text('eula=true\n', encoding='ascii')
    (directory / 'whitelist.json').write_text(json.dumps([{'uuid': str(PLAYER_ID), 'name': USERNAME}]),
                                              encoding='ascii')
    (directory / FIXTURE).mkdir()
    (directory / FIXTURE / 'disposable-test').touch(exist_ok=False)
    plugin = directory / NATIVE.parent
    plugin.mkdir(parents=True, exist_ok=True)
    (plugin / 'config.yml').write_text('native-catalog:\n  enabled: true\n', encoding='ascii')
    (plugin / 'catalog.yml').write_text('schema: 1\nvariants: {}\n', encoding='ascii')
    shutil.copy2(inputs.core, directory / 'plugins' / inputs.core.name)
    shutil.copy2(helper, directory / 'plugins' / helper.name)


def fingerprint(directory):
    return {path.relative_to(directory): sha256(path)
            for path in directory.rglob('*') if path.is_file()}


def original_fingerprints(source):
    return {path: fingerprint(source / path) for path in (Path('world'), FIXTURE, NATIVE)}


def seal(capture, source):
    database = source / DATABASE
    if any(database.with_name(database.name + suffix).exists() for suffix in ('-wal', '-shm', '-journal')):
        raise RuntimeError('Standalone source journal was not closed')
    player = Path('players') / f'{PLAYER_ID}.dat'
    files = {
        'database': (database, Path('journal.db')),
        'world': (source / DIMENSION / WORLD_METADATA, WORLD_METADATA),
        'player': (source / 'world' / 'players' / 'data' / player.name, player),
        'entities': (source / DIMENSION / ENTITY_REGION, ENTITY_REGION),
    }
    manifest = [f'capture={uuid.uuid4()}\n']
    for label, (original, relative) in files.items():
        target = capture / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(original, target)
        manifest.append(f'{label}={sha256(target)}\n')
    manifest_file = capture / 'capture.txt'
    manifest_file.write_text(''.join(manifest), encoding='ascii')
    return sha256(manifest_file)


def verify_journal(path, operation, entity, expected):
    with closing(sqlite3.connect(path.absolute().as_uri() + '?mode=ro', uri=True)) as database:
        phases = database.execute('SELECT id, phase FROM operations').fetchall()
        towers = database.execute('SELECT form, generation, active_operation FROM towers').fetchall()
        ground = database.execute('SELECT generation, entity_id, delivery_operation FROM ground_representations').fetchall()
    if expected == 'COMPLETE':
        wanted = ([('GROUND', 2, None)], [(2, str(entity), str(operation))])
    else:
        active = str(operation) if expected == 'INTENT' else None
        wanted = ([('PORTABLE', 1, active)], [])
    if phases != [(str(operation), expected)] or (towers, ground) != wanted:
        raise RuntimeError('Unexpected recovery journal or authority row')


def start_cut(inputs, source, port, helper, case):
    prepare(inputs, source, port, helper)
    tower, operation, session = uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
    with boot(inputs, source, port) as server:
        server.probe(f'prepare {tower}')
        server.probe('world')
        server.probe('entity')
        server.send(f'crashprobe stage {tower} {operation}')
        entity = uuid.UUID(server.expect('RECOVERY-STAGE '))
        server.expect('CRASH-ACK stage')
        server.probe(f'seed {tower} {operation} {entity} {session}')
        if case == 'after':
            server.probe('remove')
        if case != 'before':
            server.probe('entity')
        server.probe('player')
    return operation, entity


def run_case(inputs, root, port, helper, case, expected):
    folder = root / case
    folder.mkdir()
    source, restart = folder / 'source', folder / 'restart'
    operation, entity = start_cut(inputs, source, port, helper, case)
    pristine = original_fingerprints(source)
    prepare(inputs, restart, port, helper, source=source)
    capture = restart / FIXTURE / 'capture'
    digest = seal(capture, source)
    sealed = fingerprint(capture)
    with boot(inputs, restart, port) as server:
        server.send(f'crashprobe reconcile {operation} {digest}')
        result = tuple(server.expect('RECOVERY-RESULT ', 30).split())
        server.expect('CRASH-ACK reconcile')
    if result != expected:
        raise RuntimeError(f'Unexpected {case} outcome: {result} (expected {expected})')
    verify_journal(restart / FIXTURE / 'journal-work.db', operation, entity, expected[1])
    if original_fingerprints(source) != pristine or fingerprint(capture) != sealed:
        raise RuntimeError('Original or sealed capture bytes changed')
    for artifact in BOOT:
        path = restart / artifact
        shutil.rmtree(path) if path.is_dir() else path.unlink()
    print(f'{case}: {result[0]} {result[1]} {result[2]}; original and sealed saves unchanged', flush=True)


def main():
    inputs = parse_inputs()
    root = Path(tempfile.mkdtemp(prefix='item-drop-acceptance-', dir=inputs.scratch))
    print(f'Disposable fixture directory: {root}', flush=True)
    helper = build_fixture(inputs, root)
    with socket.socket() as probe:
        probe.bind((HOST, 0))
        port = probe.getsockname()[1]
    for case, expected in CASES.items():
        run_case(inputs, root, port, helper, case, expected)


if __name__ == '__main__':
    main()
