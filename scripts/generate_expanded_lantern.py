import json
import uuid
import math
import os

_uuid_counter = 0

def reset_uuid():
    global _uuid_counter
    _uuid_counter = 0

def make_uuid(name=""):
    global _uuid_counter
    _uuid_counter += 1
    return str(uuid.uuid5(uuid.NAMESPACE_DNS, f"infernolantern.{_uuid_counter}.{name}"))

def make_cylinder_16(name_prefix, cx, cz, y0, y1, diameter, tex_uuid, uv_box):
    R = diameter / 2.0
    t = diameter * math.tan(math.pi / 16.0)
    th = t / 2.0
    
    cubes = []
    angles = [0, 45, 22.5, -22.5]
    
    for ang in angles:
        # EW cube
        ew_cube = {
            "name": f"{name_prefix}_ew_{ang}",
            "box_uv": False,
            "rescale": False,
            "locked": False,
            "from": [round(cx - R, 4), round(y0, 4), round(cz - th, 4)],
            "to": [round(cx + R, 4), round(y1, 4), round(cz + th, 4)],
            "origin": [round(cx, 4), round(y0, 4), round(cz, 4)],
            "rotation": [0, ang, 0] if ang != 0 else [0, 0, 0],
            "color": 0,
            "faces": {
                face: {"uv": uv_box, "texture": tex_uuid} for face in ["north", "east", "south", "west", "up", "down"]
            },
            "type": "cube",
            "uuid": make_uuid()
        }
        cubes.append(ew_cube)
        
        # NS cube
        ns_cube = {
            "name": f"{name_prefix}_ns_{ang}",
            "box_uv": False,
            "rescale": False,
            "locked": False,
            "from": [round(cx - th, 4), round(y0, 4), round(cz - R, 4)],
            "to": [round(cx + th, 4), round(y1, 4), round(cz + R, 4)],
            "origin": [round(cx, 4), round(y0, 4), round(cz, 4)],
            "rotation": [0, ang, 0] if ang != 0 else [0, 0, 0],
            "color": 0,
            "faces": {
                face: {"uv": uv_box, "texture": tex_uuid} for face in ["north", "east", "south", "west", "up", "down"]
            },
            "type": "cube",
            "uuid": make_uuid()
        }
        cubes.append(ns_cube)
        
    return cubes

def build_expanded_tower():
    reset_uuid()
    tex_frame_uuid = make_uuid("tex_frame")
    tex_glow_uuid = make_uuid("tex_glow")
    
    elements = []
    groups = []
    
    cx, cz = 8.0, 8.0
    
    # UV Presets on 16-scale
    UV_BASE = [0, 0, 8, 8]
    UV_COLUMN = [0, 8, 8, 16]
    UV_ROOF = [8, 0, 16, 8]
    UV_METAL = [8, 8, 16, 16]
    UV_GLOW = [2, 2, 14, 14]
    
    # ==========================================================
    # 1. Base Pedestal (Y: 0 to 14)
    # ==========================================================
    tier1_cubes = make_cylinder_16("Tier1_BaseRim", cx, cz, 0.0, 2.5, 14.0, tex_frame_uuid, UV_BASE)
    tier2_cubes = make_cylinder_16("Tier2_PedestalBody", cx, cz, 2.5, 8.0, 11.0, tex_frame_uuid, UV_BASE)
    tier3_cubes = make_cylinder_16("Tier3_LowerCollar", cx, cz, 8.0, 11.0, 8.5, tex_frame_uuid, UV_BASE)
    tier4_cubes = make_cylinder_16("Tier4_UpperCollar", cx, cz, 11.0, 14.0, 6.5, tex_frame_uuid, UV_BASE)
    
    # Front Overview / Access Door (North face: Z ~ 2.5)
    access_door = {
        "name": "access_door_panel",
        "box_uv": False,
        "rescale": False,
        "from": [6.2, 3.0, 2.2],
        "to": [9.8, 7.5, 2.8],
        "origin": [8.0, 3.0, 2.5],
        "color": 1,
        "faces": {
            "north": {"uv": [2, 2, 10, 10], "texture": tex_frame_uuid},
            "east": {"uv": [0, 0, 2, 8], "texture": tex_frame_uuid},
            "south": {"uv": [2, 2, 10, 10], "texture": tex_frame_uuid},
            "west": {"uv": [0, 0, 2, 8], "texture": tex_frame_uuid},
            "up": {"uv": [0, 0, 8, 2], "texture": tex_frame_uuid},
            "down": {"uv": [0, 0, 8, 2], "texture": tex_frame_uuid}
        },
        "type": "cube",
        "uuid": make_uuid()
    }
    
    pedestal_cubes = tier1_cubes + tier2_cubes + tier3_cubes + tier4_cubes + [access_door]
    elements.extend(pedestal_cubes)
    
    group_pedestal = {
        "name": "Base_Pedestal",
        "origin": [8.0, 0.0, 8.0],
        "children": [c["uuid"] for c in pedestal_cubes]
    }
    groups.append(group_pedestal)
    
    # ==========================================================
    # 2. Deployed Controls (Y: 10.5 to 13.5, reachable height ~0.7m)
    # ==========================================================
    # Control 1: Light Toggle Switch (West side: X ~ 4.5)
    light_bracket = {
        "name": "light_switch_bracket",
        "from": [4.2, 10.8, 7.4],
        "to": [4.8, 12.8, 8.6],
        "origin": [4.5, 11.8, 8.0],
        "faces": {f: {"uv": UV_METAL, "texture": tex_frame_uuid} for f in ["north", "east", "south", "west", "up", "down"]},
        "type": "cube",
        "uuid": make_uuid()
    }
    light_lever = {
        "name": "light_switch_lever",
        "from": [3.6, 11.4, 7.7],
        "to": [4.2, 13.2, 8.3],
        "origin": [4.2, 11.4, 8.0],
        "rotation": [0, 0, 22.5],
        "color": 2,
        "faces": {f: {"uv": [12, 12, 16, 16], "texture": tex_frame_uuid} for f in ["north", "east", "south", "west", "up", "down"]},
        "type": "cube",
        "uuid": make_uuid()
    }
    light_controls = [light_bracket, light_lever]
    elements.extend(light_controls)
    group_light = {
        "name": "Control_Light_Switch",
        "origin": [4.5, 11.8, 8.0],
        "children": [c["uuid"] for c in light_controls]
    }
    groups.append(group_light)
    
    # Control 2: Defence Toggle Switch (East side: X ~ 11.5)
    defence_bracket = {
        "name": "defence_switch_bracket",
        "from": [11.2, 10.8, 7.4],
        "to": [11.8, 12.8, 8.6],
        "origin": [11.5, 11.8, 8.0],
        "faces": {f: {"uv": UV_METAL, "texture": tex_frame_uuid} for f in ["north", "east", "south", "west", "up", "down"]},
        "type": "cube",
        "uuid": make_uuid()
    }
    defence_lever = {
        "name": "defence_switch_lever",
        "from": [11.8, 11.4, 7.7],
        "to": [12.4, 13.2, 8.3],
        "origin": [11.8, 11.4, 8.0],
        "rotation": [0, 0, -22.5],
        "color": 3,
        "faces": {f: {"uv": [12, 12, 16, 16], "texture": tex_frame_uuid} for f in ["north", "east", "south", "west", "up", "down"]},
        "type": "cube",
        "uuid": make_uuid()
    }
    defence_controls = [defence_bracket, defence_lever]
    elements.extend(defence_controls)
    group_defence = {
        "name": "Control_Defence_Switch",
        "origin": [11.5, 11.8, 8.0],
        "children": [c["uuid"] for c in defence_controls]
    }
    groups.append(group_defence)
    
    # ==========================================================
    # 3. Column Shaft (Y: 14 to 32, Block 2)
    # ==========================================================
    column_cubes = make_cylinder_16("Tower_Column_Shaft", cx, cz, 14.0, 32.0, 3.8, tex_frame_uuid, UV_COLUMN)
    column_ring = make_cylinder_16("Tower_Column_Ring", cx, cz, 22.5, 23.5, 5.0, tex_frame_uuid, UV_METAL)
    column_all = column_cubes + column_ring
    elements.extend(column_all)
    group_column = {
        "name": "Tower_Column",
        "origin": [8.0, 14.0, 8.0],
        "children": [c["uuid"] for c in column_all]
    }
    groups.append(group_column)
    
    # ==========================================================
    # 4. Attack Chamber & Combustion Core (Y: 32 to 43, Block 3)
    # ==========================================================
    cradle_cubes = make_cylinder_16("Chamber_Cradle", cx, cz, 32.0, 34.0, 12.0, tex_frame_uuid, UV_BASE)
    core_cubes = make_cylinder_16("Chamber_Glow_Core", cx, cz, 34.0, 42.0, 6.5, tex_glow_uuid, UV_GLOW)
    
    # 4 Corner Struts (around the combustion chamber)
    struts = []
    corners = [(4.5, 4.5), (11.5, 4.5), (4.5, 11.5), (11.5, 11.5)]
    for idx, (px, pz) in enumerate(corners):
        strut = {
            "name": f"chamber_pillar_{idx}",
            "from": [px - 0.6, 34.0, pz - 0.6],
            "to": [px + 0.6, 42.0, pz + 0.6],
            "origin": [px, 34.0, pz],
            "faces": {f: {"uv": UV_COLUMN, "texture": tex_frame_uuid} for f in ["north", "east", "south", "west", "up", "down"]},
            "type": "cube",
            "uuid": make_uuid()
        }
        struts.append(strut)
        
    collar_cubes = make_cylinder_16("Chamber_UpperCollar", cx, cz, 42.0, 43.0, 10.0, tex_frame_uuid, UV_BASE)
    chamber_all = cradle_cubes + core_cubes + struts + collar_cubes
    elements.extend(chamber_all)
    group_chamber = {
        "name": "Attack_Chamber",
        "origin": [8.0, 32.0, 8.0],
        "children": [c["uuid"] for c in chamber_all]
    }
    groups.append(group_chamber)
    
    # ==========================================================
    # 5. Roof & Finial Canopy (Y: 43 to 48)
    # ==========================================================
    roof_eave = make_cylinder_16("Roof_Eave", cx, cz, 43.0, 44.5, 14.5, tex_frame_uuid, UV_ROOF)
    roof_tier1 = make_cylinder_16("Roof_Tier1", cx, cz, 44.5, 45.8, 10.5, tex_frame_uuid, UV_ROOF)
    roof_tier2 = make_cylinder_16("Roof_Tier2", cx, cz, 45.8, 46.8, 7.0, tex_frame_uuid, UV_ROOF)
    
    finial = {
        "name": "roof_finial_spire",
        "from": [7.3, 46.8, 7.3],
        "to": [8.7, 48.0, 8.7],
        "origin": [8.0, 46.8, 8.0],
        "faces": {f: {"uv": UV_METAL, "texture": tex_frame_uuid} for f in ["north", "east", "south", "west", "up", "down"]},
        "type": "cube",
        "uuid": make_uuid()
    }
    
    roof_all = roof_eave + roof_tier1 + roof_tier2 + [finial]
    elements.extend(roof_all)
    group_roof = {
        "name": "Tower_Roof",
        "origin": [8.0, 43.0, 8.0],
        "children": [c["uuid"] for c in roof_all]
    }
    groups.append(group_roof)
    
    # Root project JSON
    bbmodel = {
        "meta": {
            "format_version": "5.0",
            "model_format": "free",
            "box_uv": False
        },
        "name": "expanded_lantern",
        "resolution": {"width": 64, "height": 64},
        "elements": elements,
        "outliner": groups,
        "textures": [
            {
                "path": "textures/block/lantern_frame.png",
                "name": "lantern_frame.png",
                "folder": "block",
                "namespace": "infernolantern",
                "id": "0",
                "particle": True,
                "render_mode": "default",
                "visible": True,
                "mode": "bitmap",
                "saved": True,
                "uuid": tex_frame_uuid,
                "source": ""
            },
            {
                "path": "textures/block/lantern_glow.png",
                "name": "lantern_glow.png",
                "folder": "block",
                "namespace": "infernolantern",
                "id": "1",
                "particle": False,
                "render_mode": "default",
                "visible": True,
                "mode": "bitmap",
                "saved": True,
                "uuid": tex_glow_uuid,
                "source": ""
            }
        ]
    }
    return bbmodel

def export_normalized_java_block(model, out_path):
    tex_map = {
        model["textures"][0]["uuid"]: "#0",
        model["textures"][1]["uuid"]: "#1"
    }
    elements = []
    for el in model["elements"]:
        f_x = round(8.0 + (el["from"][0] - 8.0) / 3.0, 4)
        f_y = round(el["from"][1] / 3.0, 4)
        f_z = round(8.0 + (el["from"][2] - 8.0) / 3.0, 4)
        
        t_x = round(8.0 + (el["to"][0] - 8.0) / 3.0, 4)
        t_y = round(el["to"][1] / 3.0, 4)
        t_z = round(8.0 + (el["to"][2] - 8.0) / 3.0, 4)
        
        out_el = {
            "name": el["name"],
            "from": [min(f_x, t_x), min(f_y, t_y), min(f_z, t_z)],
            "to": [max(f_x, t_x), max(f_y, t_y), max(f_z, t_z)]
        }
        
        rot = el.get("rotation", [0, 0, 0])
        origin = el.get("origin", [8.0, 0.0, 8.0])
        o_x = round(8.0 + (origin[0] - 8.0) / 3.0, 4)
        o_y = round(origin[1] / 3.0, 4)
        o_z = round(8.0 + (origin[2] - 8.0) / 3.0, 4)
        
        if rot[0] != 0:
            out_el["rotation"] = {"angle": rot[0], "axis": "x", "origin": [o_x, o_y, o_z]}
        elif rot[1] != 0:
            out_el["rotation"] = {"angle": rot[1], "axis": "y", "origin": [o_x, o_y, o_z]}
        elif rot[2] != 0:
            out_el["rotation"] = {"angle": rot[2], "axis": "z", "origin": [o_x, o_y, o_z]}
            
        out_faces = {}
        res_w = float(model.get("resolution", {}).get("width", 64))
        res_h = float(model.get("resolution", {}).get("height", 64))
        scale_u = 16.0 / res_w
        scale_v = 16.0 / res_h
        for face_name, face_data in el["faces"].items():
            tex_id = tex_map.get(face_data.get("texture"), "#0")
            uv = face_data.get("uv", [0, 0, 16, 16])
            u0 = round(uv[0] * scale_u, 4)
            v0 = round(uv[1] * scale_v, 4)
            u1 = round(uv[2] * scale_u, 4)
            v1 = round(uv[3] * scale_v, 4)
            out_faces[face_name] = {"uv": [u0, v0, u1, v1], "texture": tex_id}
        out_el["faces"] = out_faces
        elements.append(out_el)
        
    java_block = {
        "format_version": "26.3",
        "texture_size": [64, 64],
        "textures": {
            "0": "infernolantern:block/lantern_frame",
            "1": "infernolantern:block/lantern_glow",
            "particle": "infernolantern:block/lantern_frame"
        },
        "elements": elements
    }
    with open(out_path, "w") as f:
        json.dump(java_block, f, indent=2)

def export_bedrock_geometry(model, out_path):
    uuid_to_el = {el["uuid"]: el for el in model["elements"]}
    bones = []
    for g in model["outliner"]:
        cubes = []
        for child_uuid in g["children"]:
            el = uuid_to_el[child_uuid]
            x0, y0, z0 = el["from"]
            x1, y1, z1 = el["to"]
            cube = {
                "origin": [round(-x1, 4), round(y0, 4), round(z0, 4)],
                "size": [round(x1 - x0, 4), round(y1 - y0, 4), round(z1 - z0, 4)],
            }
            rot = el.get("rotation", [0, 0, 0])
            orig = el.get("origin", [8.0, 0.0, 8.0])
            if any(r != 0 for r in rot):
                cube["pivot"] = [round(-orig[0], 4), round(orig[1], 4), round(orig[2], 4)]
                cube["rotation"] = rot
            cube["uv"] = {
                face: {
                    "uv": [round(el["faces"][face]["uv"][0], 2), round(el["faces"][face]["uv"][1], 2)],
                    "uv_size": [round(el["faces"][face]["uv"][2] - el["faces"][face]["uv"][0], 2),
                                round(el["faces"][face]["uv"][3] - el["faces"][face]["uv"][1], 2)]
                }
                for face in ["north", "east", "south", "west", "up", "down"]
            }
            cubes.append(cube)
        g_orig = g.get("origin", [8.0, 0.0, 8.0])
        bone = {
            "name": g["name"],
            "pivot": [round(-g_orig[0], 4), round(g_orig[1], 4), round(g_orig[2], 4)],
            "cubes": cubes
        }
        bones.append(bone)
        
    geo = {
        "format_version": "1.12.0",
        "minecraft:geometry": [
            {
                "description": {
                    "identifier": "geometry.infernolantern.expanded_lantern",
                    "texture_width": 64,
                    "texture_height": 64,
                    "visible_bounds_width": 2,
                    "visible_bounds_height": 4,
                    "visible_bounds_offset": [0, 1.5, 0]
                },
                "bones": bones
            }
        ]
    }
    with open(out_path, "w") as f:
        json.dump(geo, f, indent=2)

if __name__ == '__main__':
    model = build_expanded_tower()
    bbmodel_path = 'src/main/resources/assets/infernolantern/models/expanded_lantern.bbmodel'
    block_json_path = 'src/main/resources/assets/infernolantern/models/block/expanded_lantern.json'
    bedrock_geo_path = 'src/main/resources/assets/infernolantern/models/bedrock/expanded_lantern.geo.json'
    
    with open(bbmodel_path, 'w') as f:
        json.dump(model, f, indent=2)
    print(f"Generated {bbmodel_path} ({len(model['elements'])} elements, {len(model['outliner'])} root groups).")
    
    export_normalized_java_block(model, block_json_path)
    print(f"Exported normalized Java block model: {block_json_path}")
    
    export_bedrock_geometry(model, bedrock_geo_path)
    print(f"Exported Bedrock geometry: {bedrock_geo_path}")
