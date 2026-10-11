"""Convert an oracle biome-tree dump (pre-order N/L lines) into a C header.

usage: py -I tree2c.py <biome-tree.txt> <cubiomes biomes.h> <name> <out.h> <mc version>
Leaf biomes use cubiomes enum ids; biomes cubiomes does not know get ids from 230 up.
"""
import re, sys

dump, biomes_h, name, out, ver = sys.argv[1:6]

# cubiomes enum: sequential values plus explicit "x = N" / "alias = x" assignments
ids, cur = {}, -1
body = open(biomes_h, encoding='utf8').read()
body = body[body.index('enum BiomeID'):]
body = body[:body.index('};')]
for line in body.splitlines():
    line = line.split('//')[0]
    for part in [p.strip() for p in line.split(',') if p.strip() and p.strip() != '{']:
        m = re.match(r'^(\w+)\s*(?:=\s*(.+))?$', part)
        if not m:
            continue
        k, v = m.group(1), m.group(2)
        if v is None:
            cur += 1
            ids[k] = cur
        else:
            v = v.strip()
            if re.match(r'^-?\d+$', v):
                cur = int(v)
                ids[k] = cur
            elif '+' in v:
                a, b = v.split('+')
                ids[k] = ids[a.strip()] + int(b)
            else:
                ids[k] = ids[v]

# fixed ids for biomes cubiomes lacks, so every generated table agrees
FIXED = {'dappled_forest': 230, 'sulfur_caves': 231}
extra = {}
nodes = []      # (is_leaf, biome_id, params[14], children[])
stack = []
for line in open(dump, encoding='utf8'):
    f = line.split()
    if not f:
        continue
    params = [int(x) for x in f[2:16]]
    if f[0] == 'L':
        b = f[1]
        if b not in ids:
            extra.setdefault(b, FIXED.get(b, 240 + len(extra)))
            bid = extra[b]
        else:
            bid = ids[b]
        nodes.append((1, bid, params, []))
    else:
        nodes.append((0, int(f[1]), params, []))
    idx = len(nodes) - 1
    if stack:
        parent, left = stack[-1]
        nodes[parent][3].append(idx)
        stack[-1] = (parent, left - 1)
        if left - 1 == 0:
            stack.pop()
    if f[0] == 'N':
        stack.append((idx, int(f[1])))
assert not stack, 'truncated dump'

kids = []
first = []
for n in nodes:
    first.append(len(kids))
    kids.extend(n[3])

with open(out, 'w', encoding='utf8', newline='\n') as w:
    w.write(f'''/* GENERATED DATA -- do not hand-edit.
 *
 * Minecraft {ver}'s overworld `Climate.RTree` (the tree `MultiNoiseBiomeSource.getNoiseBiome`
 * searches), dumped node by node from the running game by C:\\CODE\\outpost-oracle
 * (reflection on Climate.ParameterList.index) and converted by _mcdata\\tree2c.py.
 * cubiomes has no table for this version, so src/biome.c searches this tree with a
 * straight port of `Climate.RTree.SubTree.search` instead of the cubiomes walker.
 *
 * Node i: params[i] = 7 x (min, max) quantized ranges (temperature humidity continentalness
 * erosion depth weirdness offset); leaf[i] = cubiomes biome id or -1 for an inner node whose
 * children are kids[first[i] .. first[i] + count[i]).
 * Biomes cubiomes does not know: {", ".join(f"{k} = {v}" for k, v in extra.items()) or "none"}.
 */
#include <inttypes.h>

enum {{ {name}_len = {len(nodes)} }};

static const int32_t {name}_params[][14] = {{
''')
    for n in nodes:
        w.write('    {' + ','.join(str(p) for p in n[2]) + '},\n')
    w.write(f'}};\n\nstatic const int16_t {name}_leaf[] = {{\n')
    for i in range(0, len(nodes), 20):
        w.write('    ' + ','.join(str(n[1] if n[0] else -1) for n in nodes[i:i + 20]) + ',\n')
    w.write(f'}};\n\nstatic const uint16_t {name}_count[] = {{\n')
    for i in range(0, len(nodes), 20):
        w.write('    ' + ','.join(str(len(n[3])) for n in nodes[i:i + 20]) + ',\n')
    w.write(f'}};\n\nstatic const uint16_t {name}_first[] = {{\n')
    for i in range(0, len(nodes), 20):
        w.write('    ' + ','.join(str(x) for x in first[i:i + 20]) + ',\n')
    w.write(f'}};\n\nstatic const uint16_t {name}_kids[] = {{\n')
    for i in range(0, len(kids), 20):
        w.write('    ' + ','.join(str(x) for x in kids[i:i + 20]) + ',\n')
    w.write('};\n')
print(len(nodes), 'nodes', len(kids), 'kids; extra ids', extra,
      '; dappled', extra.get('dappled_forest'), 'plains', ids['plains'], 'swamp', ids['swamp'])
