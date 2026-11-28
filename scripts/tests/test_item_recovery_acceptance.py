from pathlib import Path
import tempfile
import unittest

from scripts import run_item_recovery_acceptance as runner


class FixtureArtifactTests(unittest.TestCase):
    def test_exact_artifact_digest_is_required(self):
        with tempfile.TemporaryDirectory() as temporary:
            artifact = Path(temporary) / 'test.jar'
            artifact.write_bytes(b'test fixture')
            checksum = runner.sha256(artifact)
            runner.verify_digest(artifact, checksum)
            with self.assertRaises(ValueError):
                runner.verify_digest(artifact, checksum.upper())
            artifact.write_bytes(b'changed fixture')
            with self.assertRaises(ValueError):
                runner.verify_digest(artifact, checksum)

    def test_seal_records_raw_part_digests_and_keeps_source_unchanged(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, capture = root / 'source', root / 'capture'
            files = {
                runner.DATABASE: b'closed database',
                runner.DIMENSION / runner.WORLD_METADATA: b'Paper world metadata',
                Path('world/players/data') / f'{runner.PLAYER_ID}.dat': b'Paper player',
                runner.DIMENSION / runner.ENTITY_REGION: b'Paper entity region',
            }
            for relative, payload in files.items():
                file = source / relative
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_bytes(payload)
            original = runner.fingerprint(source)
            digest = runner.seal(capture, source)
            manifest = capture / 'capture.txt'
            self.assertEqual(digest, runner.sha256(manifest))
            fields = dict(line.split('=', 1) for line in manifest.read_text().splitlines())
            for name, relative in {
                'database': Path('journal.db'), 'world': runner.WORLD_METADATA,
                'player': Path('players') / f'{runner.PLAYER_ID}.dat', 'entities': runner.ENTITY_REGION,
            }.items():
                self.assertEqual(fields[name], runner.sha256(capture / relative))
            self.assertEqual(original, runner.fingerprint(source))

    def test_seal_refuses_a_journal_sidecar(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            journal = root / 'source' / runner.DATABASE
            journal.parent.mkdir(parents=True)
            journal.write_bytes(b'journal')
            for ending in ('-wal', '-shm', '-journal'):
                sidecar = journal.with_name(journal.name + ending)
                sidecar.write_bytes(b'preserved')
                with self.assertRaisesRegex(RuntimeError, 'was not closed'):
                    runner.seal(root / 'capture', root / 'source')
                self.assertEqual(b'preserved', sidecar.read_bytes())
                self.assertFalse((root / 'capture').exists())
                sidecar.unlink()
