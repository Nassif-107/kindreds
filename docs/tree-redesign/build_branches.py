"""Per-branch capability summaries: what a branch actually lets you do, in both languages.

Generated from the trees, so it cannot drift; the perk vocabulary is named properly
rather than shown as raw ids.
"""
import json, glob, os, collections

RES = 'C:/dev/minecraft_mods/new-mod/src/main/resources'
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'kindreds-branches.json')

PERK = {
 'ally_aura':        ('steady your companions', 'поддерживает спутников'),
 'ambush':           ('heavier blows from hiding', 'удары из укрытия тяжелее'),
 'arrow_crit':       ('arrows strike critically', 'стрелы бьют критически'),
 'arrow_damage':     ('harder-hitting arrows', 'стрелы бьют сильнее'),
 'arrow_effect':     ('arrows carry an effect', 'стрелы несут эффект'),
 'arrow_pierce':     ('arrows punch through a file of foes', 'стрелы пробивают строй'),
 'arrow_slaying':    ('arrows bite deeper into old enemies', 'стрелы глубже жалят исконных врагов'),
 'arrow_velocity':   ('flatter, faster arrows', 'стрелы летят быстрее и ровнее'),
 'auto_smelt':       ('ore smelts as you mine it', 'руда плавится прямо при добыче'),
 'bane':             ('heavier blows against your ancient foes', 'удары тяжелее против исконных врагов'),
 'beast_calm':       ('beasts do not turn on you', 'звери не нападают'),
 'camouflage':       ('vanish while sneaking', 'исчезновение в приседе'),
 'dread_aura':       ('nearby foes falter', 'враги рядом слабеют'),
 'elven_steed':      ('your mount runs faster', 'ваш скакун быстрее'),
 'evasion':          ('some blows simply miss', 'часть ударов проходит мимо'),
 'foresight':        ('you take less from what you saw coming', 'меньше урона от предвиденного'),
 'heal_on_kill':     ('a kill heals you', 'убийство лечит'),
 'lifesteal':        ('damage dealt returns as health', 'часть урона возвращается здоровьем'),
 'long_shot':        ('distance adds to the shot', 'дальность добавляет урона'),
 'mend_gear':        ('worn gear mends itself', 'снаряжение чинится само'),
 'miners_rhythm':    ('mining builds speed', 'добыча разгоняет темп'),
 'mining_fortune':   ('ore sometimes yields extra', 'руда иногда даёт больше'),
 'multishot':        ('loose several arrows at once', 'залп из нескольких стрел'),
 'ore_magnet':       ('drops come to your hand', 'выпавшее само идёт в руки'),
 'pack_bond':        ('your wolves fight harder', 'ваши волки дерутся яростнее'),
 'pounce':           ('leap onto your prey', 'прыжок на добычу'),
 'prey_sense':       ('living things glow to your eye', 'живое светится для вашего глаза'),
 'prospector_xp':    ('ore gives more experience', 'руда даёт больше опыта'),
 'strike_effect':    ('your hits carry poison or worse', 'удары несут яд или хуже'),
 'swift_draw':       ('draw the bow faster', 'быстрее натягивается лук'),
 'thorns':           ('attackers cut themselves on you', 'нападающие ранятся сами'),
 'true_flight':      ('arrows bend toward the mark', 'стрелы подворачивают к цели'),
 'unyielding':       ('cannot be slowed or tired', 'нельзя замедлить или утомить'),
 'vein_miner':       ('a whole vein falls at one stroke', 'вся жила падает с одного удара'),
 'war_pack':         ('stronger with allies beside you', 'сильнее, когда рядом союзники'),
 'war_steed':        ('your mount fights with you', 'ваш скакун бьётся с вами'),
}

DISC = {
 'combat': ('Combat', 'Бой'), 'archery': ('Archery', 'Стрельба'), 'mining': ('Mining', 'Горное дело'),
 'stealth': ('Stealth', 'Скрытность'), 'smithing': ('Smithing', 'Кузнечное дело'),
 'survival': ('Survival', 'Выживание'), 'lore': ('Lore', 'Знание'), 'song': ('Song', 'Песнь'),
 'beast_lore': ('Beast-lore', 'Звериное знание'), 'leadership': ('Leadership', 'Предводительство'),
 'runecraft': ('Rune-craft', 'Рунное ремесло'), 'shadow': ('Shadow', 'Тень'),
}

CTX = {'starlight': ('under starlight', 'под звёздами'), 'daylight': ('in daylight', 'при свете дня'),
       'darkness': ('in darkness', 'во тьме'), 'deep_dark': ('in the deep dark', 'в глубокой тьме'),
       'underground': ('underground', 'под землёй'), 'low_health': ('at low health', 'на грани смерти'),
       'dawn_dusk': ('at dawn and dusk', 'на заре и в сумерках')}


EN = json.load(open(RES + '/assets/kindreds/lang/en_us.json', encoding='utf-8'))
RU = json.load(open(RES + '/assets/kindreds/lang/ru_ru.json', encoding='utf-8'))


def title(s):
    return s.replace('_', ' ').title()


out = {}
for f in sorted(glob.glob(RES + '/data/kindreds/kindreds/skill_tree/*.json')):
    race = os.path.basename(f)[:-5]
    tree = json.load(open(f, encoding='utf-8'))
    branches = {}
    for n in tree['nodes']:
        d = n['cost']['discipline'].split(':')[-1]
        b = branches.setdefault(d, {'nodes': 0, 'points': 0, 'actives': [], 'perks': collections.Counter(),
                                    'ctx': collections.Counter(), 'sealed': 0})
        b['nodes'] += 1
        b['points'] += n['cost']['points']
        if n.get('deed_advancement'):
            b['sealed'] += 1
        for a in n.get('abilities', []):
            t = a.get('type')
            if t == 'active':
                key = 'kindreds.ability.' + a['ability_id'].split(':')[-1]
                nm = {'en': EN.get(key) or title(a['ability_id'].split(':')[-1]),
                      'ru': RU.get(key) or EN.get(key) or title(a['ability_id'].split(':')[-1])}
                if nm not in b['actives']:
                    b['actives'].append(nm)
            elif t == 'perk':
                b['perks'][a['perk'].split(':')[-1]] += 1
            elif t == 'contextual_boon':
                b['ctx'][a.get('when')] += 1

    rows = []
    for d, b in branches.items():
        top = [p for p, _ in b['perks'].most_common(4)]
        ctxs = [c for c, _ in b['ctx'].most_common(2)]
        rows.append({
            'id': d,
            'name': {'en': DISC.get(d, (title(d), title(d)))[0], 'ru': DISC.get(d, (title(d), title(d)))[1]},
            'nodes': b['nodes'], 'points': b['points'], 'sealed': b['sealed'],
            'actives': {'en': [x['en'] for x in b['actives'][:4]],
                        'ru': [x['ru'] for x in b['actives'][:4]]},
            'gives': {
                'en': [PERK.get(p, (p.replace('_', ' '), p))[0] for p in top],
                'ru': [PERK.get(p, (p.replace('_', ' '), p))[1] for p in top],
            },
            'ctx': {
                'en': [CTX.get(c, (c, c))[0] for c in ctxs],
                'ru': [CTX.get(c, (c, c))[1] for c in ctxs],
            },
        })
    out[race] = rows

json.dump(out, open(OUT, 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
print('branch summaries for %d kindreds (%.0f KB)' % (len(out), os.path.getsize(OUT) / 1024))
for r in out['elf']:
    print('  elf/%-11s %2d skills %3d pts  actives: %s' % (r['id'], r['nodes'], r['points'],
                                                           ', '.join(r['actives']['en']) or '-'))
    print('               gives: %s' % '; '.join(r['gives']['en']))
