"""Turn Fire OS's content-support catalogue into the app's voice catalogue.

Usage: make-catalog.py CONTENTSUPPORT_JSON OUT_JSON
Only the TTS voices are kept. Each pack downloads from Amazon's own bucket, the
same address a Fire tablet builds from the entry.
"""
import json
import re
import sys

# Packs from Ivona build 3586 (2025) are the compact neural voices; every other
# build is a classic unit-selection voice.
NEURAL_BUILDS = {'3586'}

src, out = sys.argv[1], sys.argv[2]
packs = json.load(open(src))['supportPacks']
voices = []
for p in packs:
    if p.get('type') != 'TTS_VOICE':
        continue
    key = p['install_location'].strip('/').rsplit('/', 1)[1]
    if not re.fullmatch(r'[a-z]{3}\.[a-z]{3}\.[a-z]+', key):
        sys.exit(f'make-catalog: unexpected install location {p["install_location"]}')
    build = p['source_url'].split('/')[2]
    voices.append({
        'key': key,
        'name': p['display_name'],
        'languages': p.get('languages') or [p['language']],
        'kind': 'neural' if build in NEURAL_BUILDS else 'classic',
        'url': 'https://s3.amazonaws.com/' + p['source_url'],
        'bytes': int(p['size']),
        'version': p['current_version'],
    })
voices.sort(key=lambda v: (v['languages'][0], v['name']))
with open(out, 'w') as f:
    json.dump({'voices': voices}, f, indent=1, ensure_ascii=False)
    f.write('\n')
print(f'make-catalog: {len(voices)} voices, '
      f'{sum(v["kind"] == "neural" for v in voices)} neural, '
      f'{len({v["languages"][0] for v in voices})} languages')
