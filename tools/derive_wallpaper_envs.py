#!/usr/bin/env python3
"""T7-b (S20-cont REVISED): the wallpaper corpus dayEnv/nightEnv derivation.

Grounded in the decomp's static WorldEnv pair table (effects.c:220-267
D_800A9F80..D_800A9FA4) + the map-type families (map.h e_MapType), via the
PoC repo's tools/room_env_table.json (the verbatim per-room {presetIdx0,
presetIdx1, field17} table).

THE DERIVATION (the engine-true pair semantics):

    WorldEnv_PresetsSet(idx0, idx1) keeps TWO states —
    worldEnv.transition[WorldEnv_IsFlashlightOn()]: idx0 is the
    FLASHLIGHT-OFF state and idx1 the FLASHLIGHT-ON state (effects.c
    WorldEnv_SnowRainColorGet reads transition[IsFlashlightOn()]).

  * {1,1} (the town day pair)          -> day 1, night 6   (the day street /
                                          the foggy night street — the town's
                                          night keeps its fog; the beam is
                                          not the street look)
  * {2,2} (the hospital interiors)    -> day 2, night 7   (the pale fog
                                          hospital / the nightmare hospital)
  * {6,3} (night-only: SPU/DR/APU/DRU/RSU/RSR)
                                       -> day 6, night 3   (the dark
                                          flashlight-OFF base / the
                                          FLASHLIGHT-ON night — the in-game
                                          experience of those rooms)
  * {7,4} (nightmare-only: SU/ER/HU/SC) -> day 7, night 4
  * identical/dark pairs otherwise     -> day p0, night p1 (unbiased)

WHY nightEnv = the pair's ON member (the S20-cont revision of the v41
6/6-7/7 rule): shLensFlareExec's Gate 1 (bodyprog_80055028.c:102-106) gates
the WHOLE water branch on lightMode == Spot && flashlightLensFlareIntensity
!= 0 — the caustic ring + the glint + the DR_WAVE floor scroll (the water FX
the owner calls the session's main focus) exist ONLY in the flashlight-ON
state. (6,6)/(7,7) boots the identical pair, which the engine flags
isFlashlightUnavailable and auto-off — a dead flashlight, an unreachable
water. The ON member IS the nighttime one the game is played in (the user's
own authored camdat uses nightEnv 3; the camtrack's authentic default pair
is day 1 / night 3). dayEnv keeps the OFF member — the calm dark base —
so night-only scenes NEVER render as daylight (the owner's rule) and "the
flashlight will not fire in day modes" (the beam only at night).

weatherTarget stays the room's verbatim field17 in every case (NO RAIN
INDOORS unless the room's own header says so — the weather fields are the
unbiased original configuration; only the day/night phase presets are the
apps' policy).
"""
import json, os, sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CAMDAT = os.path.join(REPO, 'app/src/main/assets/sh1/camdat.json')
TABLE = os.environ.get('ROOM_ENV_TABLE',
                       os.path.join(REPO, '..', 'PoC', 'tools', 'room_env_table.json'))

def derive(room):
    p0 = int(room['presetIdx0']); p1 = int(room['presetIdx1'])
    tag = str(room.get('tag', '')).upper()
    if (p0, p1) == (1, 1):
        day, night = 1, 6
    elif (p0, p1) == (2, 2):
        day, night = 2, 7
    elif (p0, p1) == (6, 3):
        day, night = 6, 3          # night-only: OFF base / ON night (the beam+water)
    elif (p0, p1) == (7, 4):
        day, night = 7, 4          # nightmare-only: OFF base / ON night
    elif (p0, p1) == (9, 9):
        day, night = 9, 9
    else:
        day, night = p0, p1        # unbiased for any other pair
    return day, night

def main():
    d = json.load(open(CAMDAT))
    if not os.path.exists(TABLE):
        print('room_env_table.json not found at', TABLE)
        print('point ROOM_ENV_TABLE at the PoC checkout\'s '
              'tools/room_env_table.json (the verbatim per-room pair table)')
        sys.exit(1)
    table = json.load(open(TABLE))
    changed = 0
    for m in d['models']:
        base = os.path.basename(m['file'])
        room = base.rsplit('.', 1)[0]
        info = table.get(room)
        if not info:
            print('MISSING room entry:', room); sys.exit(1)
        day, night = derive(info)
        e = m.get('env') or {}
        if e.get('dayEnv') != day or e.get('nightEnv') != night:
            e['dayEnv'] = day
            e['nightEnv'] = night
            m['env'] = e
            changed += 1
        assert e.get('pair') == [info['presetIdx0'], info['presetIdx1']], (room, e.get('pair'))
        assert e.get('weatherTarget') == info['field17'], (room, e.get('weatherTarget'), info['field17'])
    json.dump(d, open(CAMDAT, 'w'), indent=2)
    print('models:', len(d['models']), 'dayEnv/nightEnv updated:', changed)

if __name__ == '__main__':
    main()
