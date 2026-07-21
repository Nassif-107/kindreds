"""Extract everything the explorer needs from the mod itself, into one compact JSON."""
import json, glob, os, re

ROOT = 'C:/dev/minecraft_mods/new-mod'
RES = ROOT + '/src/main/resources'
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'kindreds-data.json')

en = json.load(open(RES + '/assets/kindreds/lang/en_us.json', encoding='utf-8'))
ru = json.load(open(RES + '/assets/kindreds/lang/ru_ru.json', encoding='utf-8'))

def tr(node, lang):
    """A display field is either a literal {"text": ...} or a {"translate": key} to look up."""
    if 'text' in node:
        return node['text']
    key = node['translate']
    return lang.get(key, key)


RACE_ORDER = ['elf', 'dwarf', 'human', 'hobbit', 'uruk', 'orc', 'snaga', 'goblin']
RACE_EN = {'elf': 'Elves', 'dwarf': 'Dwarves', 'human': 'Men', 'hobbit': 'Hobbits',
           'uruk': 'Uruk-hai', 'orc': 'Orcs', 'snaga': 'Snaga', 'goblin': 'Goblins'}
RACE_RU = {'elf': 'Эльфы', 'dwarf': 'Гномы', 'human': 'Люди', 'hobbit': 'Хоббиты',
           'uruk': 'Урук-хай', 'orc': 'Орки', 'snaga': 'Снага', 'goblin': 'Гоблины'}
# One line on what the kindred IS, from the trees themselves.
BLURB_EN = {
    'elf': 'The Firstborn. Power drawn from starlight and song, not from haste - the most active abilities of any kindred, and the deepest bow-craft.',
    'dwarf': 'Durin’s Folk. Four lanes instead of five, each dug deeper: stone, forge, rune and axe. Immovable, and the only kindred who mends what they carry.',
    'human': 'The Second-born. Kings, riders and bowmen of Dale - the broadest kindred, strongest when others follow them.',
    'hobbit': 'The Little Folk. Nothing in Middle-earth goes unseen like a Hobbit, and nothing recovers faster over a proper meal.',
    'uruk': 'The fighting Uruk-hai. Made, not born: sun-proof, unwearied, and impossible to slow.',
    'orc': 'Servants of the Eye. Bold in the dark, wretched in daylight, and never alone.',
    'snaga': 'The lowest of the orc-kind. Scavenger and coward - and lethal exactly when cornered.',
    'goblin': 'Goblin-town. Tunnel-dwellers who climb sheer stone and make wicked things that go off.',
}
BLURB_RU = {
    'elf': 'Перворождённые. Сила от звёздного света и песни, а не от спешки: больше всего активных умений и глубочайшее искусство лука.',
    'dwarf': 'Народ Дурина. Четыре ветви вместо пяти, но каждая глубже: камень, горн, руна и топор. Несдвигаемы и чинят своё снаряжение.',
    'human': 'Пришедшие следом. Короли, всадники и лучники Дейла — самый широкий народ, сильнейший, когда за ним идут.',
    'hobbit': 'Малый народ. Никто в Средиземье не проходит так незаметно и не восстанавливается так быстро за сытной трапезой.',
    'uruk': 'Бойцы Урук-хай. Сделаны, а не рождены: не боятся солнца, не знают усталости, их невозможно замедлить.',
    'orc': 'Слуги Ока. Дерзки во тьме, жалки при свете дня и никогда не одни.',
    'snaga': '低ший из орочьего рода. Падальщик и трус — и смертельно опасен, когда загнан в угол.',
    'goblin': 'Гоблинт-таун. Обитатели туннелей: взбираются по отвесному камню и мастерят пакостные штуки, которые взрываются.',
}
BLURB_RU['snaga'] = 'Низший из орочьего рода. Падальщик и трус — и смертельно опасен, когда загнан в угол.'
BLURB_RU['goblin'] = 'Гоблин-таун. Обитатели туннелей: взбираются по отвесному камню и мастерят пакостные штуки, которые взрываются.'

DISC_EN = {'combat': 'Combat', 'archery': 'Archery', 'mining': 'Mining', 'stealth': 'Stealth',
           'smithing': 'Smithing', 'survival': 'Survival', 'lore': 'Lore', 'song': 'Song',
           'beast_lore': 'Beast-lore', 'leadership': 'Leadership', 'runecraft': 'Rune-craft',
           'shadow': 'Shadow'}
DISC_RU = {'combat': 'Бой', 'archery': 'Стрельба', 'mining': 'Горное дело', 'stealth': 'Скрытность',
           'smithing': 'Кузнечное дело', 'survival': 'Выживание', 'lore': 'Знание', 'song': 'Песнь',
           'beast_lore': 'Звериное знание', 'leadership': 'Предводительство',
           'runecraft': 'Рунное ремесло', 'shadow': 'Тень'}

CTX_EN = {'starlight': 'under starlight', 'daylight': 'in daylight', 'darkness': 'in darkness',
          'deep_dark': 'in the deep dark', 'underground': 'underground', 'dawn_dusk': 'at dawn and dusk',
          'low_health': 'at low health'}
CTX_RU = {'starlight': 'под звёздами', 'daylight': 'при свете дня', 'darkness': 'во тьме',
          'deep_dark': 'в глубокой тьме', 'underground': 'под землёй', 'dawn_dusk': 'на заре и в сумерках',
          'low_health': 'при малом здоровье'}



# --- vocabulary: everything an effect line can name, in both tongues -----------
ATTR = {
 'max_health': ('max health', 'к здоровью'), 'attack_damage': ('melee damage', 'к урону'),
 'attack_speed': ('attack speed', 'к скорости атаки'), 'movement_speed': ('movement speed', 'к скорости'),
 'knockback_resistance': ('knockback resistance', 'к сопротивлению отбрасыванию'),
 'mining_efficiency': ('mining speed', 'к скорости добычи'),
 'burning_time': ('burning time', 'ко времени горения'),
 'safe_fall_distance': ('safe fall distance', 'к безопасному падению'),
 'luck': ('luck', 'к удаче'), 'sneaking_speed': ('sneaking speed', 'к скорости в приседе'),
 'movement_efficiency': ('footing on bad ground', 'к устойчивости'),
 'block_interaction_range': ('block reach', 'к дальности до блоков'),
 'entity_interaction_range': ('attack reach', 'к дальности удара'),
 'armor': ('armour', 'к броне'), 'armor_toughness': ('armour toughness', 'к прочности брони'),
 'max_absorption': ('absorption', 'к поглощению'), 'jump_strength': ('jump strength', 'к прыжку'),
 'step_height': ('step height', 'к высоте шага'), 'oxygen_bonus': ('breath', 'к запасу воздуха'),
 'submerged_mining_speed': ('mining underwater', 'к добыче под водой'),
 'water_movement_efficiency': ('movement in water', 'к движению в воде'),
 'explosion_knockback_resistance': ('explosion knockback resistance', 'к устойчивости при взрывах'),
 'detection_range': ('how far mobs notice you', 'к дальности обнаружения'),
 'climbing_strength': ('climbing', 'к лазанию'),
 'powdered_snow_immunity': ('immunity to freezing', 'к иммунитету к обморожению'),
}
PERKS = {
 'ally_aura': ('steady your companions', 'поддержка спутников'),
 'ambush': ('heavier blows from hiding', 'удар из укрытия сильнее'),
 'arrow_crit': ('critical arrows', 'критические стрелы'),
 'arrow_damage': ('harder-hitting arrows', 'стрелы сильнее'),
 'arrow_effect': ('arrows carry an effect', 'стрелы несут эффект'),
 'arrow_pierce': ('arrows pierce a file of foes', 'стрелы пробивают строй'),
 'arrow_slaying': ('arrows bite old enemies', 'стрелы жалят исконных врагов'),
 'arrow_velocity': ('faster arrows', 'стрелы быстрее'),
 'auto_smelt': ('ore smelts as you mine', 'руда плавится при добыче'),
 'bane': ('bane of your ancient foes', 'гроза исконных врагов'),
 'beast_calm': ('beasts do not turn on you', 'звери не нападают'),
 'camouflage': ('vanish while sneaking', 'исчезновение в приседе'),
 'dread_aura': ('nearby foes falter', 'враги рядом слабеют'),
 'elven_steed': ('your mount runs faster', 'скакун быстрее'),
 'evasion': ('some blows simply miss', 'часть ударов мимо'),
 'foresight': ('less from what you saw coming', 'меньше урона от предвиденного'),
 'heal_on_kill': ('a kill heals you', 'убийство лечит'),
 'lifesteal': ('damage returns as health', 'урон возвращается здоровьем'),
 'long_shot': ('distance adds to the shot', 'дальность добавляет урона'),
 'mend_gear': ('gear mends itself', 'снаряжение чинится само'),
 'miners_rhythm': ('mining builds speed', 'добыча разгоняет темп'),
 'mining_fortune': ('ore sometimes yields extra', 'руда иногда даёт больше'),
 'multishot': ('several arrows at once', 'несколько стрел разом'),
 'ore_magnet': ('drops come to your hand', 'выпавшее идёт в руки'),
 'pack_bond': ('your wolves fight harder', 'волки дерутся яростнее'),
 'pounce': ('leap onto your prey', 'прыжок на добычу'),
 'prey_sense': ('living things glow', 'живое светится'),
 'prospector_xp': ('ore gives more experience', 'руда даёт больше опыта'),
 'strike_effect': ('hits carry poison or worse', 'удары несут яд или хуже'),
 'swift_draw': ('draw the bow faster', 'лук натягивается быстрее'),
 'thorns': ('attackers cut themselves', 'нападающие ранятся сами'),
 'true_flight': ('arrows bend toward the mark', 'стрелы подворачивают к цели'),
 'unyielding': ('cannot be slowed or tired', 'нельзя замедлить или утомить'),
 'vein_miner': ('a whole vein at one stroke', 'вся жила с одного удара'),
 'war_pack': ('stronger with allies beside you', 'сильнее рядом с союзниками'),
 'war_steed': ('your mount fights with you', 'скакун бьётся с вами'),
}
EFFECTS = {
 'night_vision': ('Night Vision', 'Ночное зрение'), 'saturation': ('Saturation', 'Насыщение'),
 'regeneration': ('Regeneration', 'Регенерация'), 'haste': ('Haste', 'Спешка'),
 'resistance': ('Resistance', 'Сопротивление'), 'weakness': ('Weakness', 'Слабость'),
 'slowness': ('Slowness', 'Медлительность'), 'speed': ('Speed', 'Скорость'),
 'strength': ('Strength', 'Сила'), 'absorption': ('Absorption', 'Поглощение'),
 'fire_resistance': ('Fire Resistance', 'Огнестойкость'), 'invisibility': ('Invisibility', 'Невидимость'),
 'jump_boost': ('Jump Boost', 'Прыгучесть'), 'poison': ('Poison', 'Отравление'),
 'wither': ('Wither', 'Иссушение'), 'nausea': ('Nausea', 'Тошнота'),
 'mining_fatigue': ('Mining Fatigue', 'Усталость'), 'blindness': ('Blindness', 'Слепота'),
 'glowing': ('Glowing', 'Свечение'), 'water_breathing': ('Water Breathing', 'Подводное дыхание'),
 'slow_falling': ('Slow Falling', 'Медленное падение'), 'health_boost': ('Health Boost', 'Прибавка здоровья'),
}


def vocab(table, key, ru):
    key = key.split(':')[-1]
    hit = table.get(key)
    if hit:
        return hit[1] if ru else hit[0]
    return key.replace('_', ' ')


def pretty(idstr):
    return idstr.split(':')[-1].replace('_', ' ')


def effect_text(a, lang):
    """One human line for an ability, in EN or RU."""
    t = a.get('type')
    ru_ = lang == 'ru'
    if t == 'attribute':
        amt = a.get('amount', 0)
        op = a.get('operation', '')
        name = vocab(ATTR, a['attribute'], ru_)
        if 'multiplied' in op:
            val = ('%+d%%' % round(amt * 100))
        else:
            val = ('%+g' % amt)
        return ('%s %s' % (val, name))
    if t == 'perk':
        p = vocab(PERKS, a['perk'], ru_)
        params = a.get('params') or {}
        extra = ', '.join('%g' % v for k, v in params.items())
        return p + (' (%s)' % extra if extra else '')
    if t == 'active':
        cd = a.get('cooldown_ticks', 0) / 20.0
        label = 'Active' if not ru_ else 'Умение'
        secs = ('%gs' % cd) if not ru_ else ('%gс' % cd)
        key = 'kindreds.ability.' + a['ability_id'].split(':')[-1]
        table = ru if ru_ else en
        name = table.get(key) or en.get(key) or pretty(a['ability_id']).title()
        return '%s: %s · %s' % (label, name, secs)
    if t == 'contextual_boon':
        ctx = (CTX_RU if ru_ else CTX_EN).get(a.get('when'), a.get('when'))
        inner = effect_text(a.get('effect', {}), lang)
        return '%s: %s' % (ctx, inner)
    if t == 'status_effect':
        amp = a.get('amplifier', 0)
        return '%s %s' % (vocab(EFFECTS, a['effect'], ru_), 'I II III IV'.split()[min(amp, 3)])
    if t == 'vision_unlock':
        return ('Vision: ' if not ru_ else 'Зрение: ') + pretty(a.get('lens', ''))
    if t == 'curse':
        return ('Curse: ' if not ru_ else 'Проклятие: ') + effect_text(a.get('effect', {}), lang)
    return t or '?'


races = {}
for f in sorted(glob.glob(RES + '/data/kindreds/kindreds/skill_tree/*.json')):
    race = os.path.basename(f)[:-5]
    tree = json.load(open(f, encoding='utf-8'))
    theme = json.load(open(RES + '/data/kindreds/kindreds/theme/%s.json' % race, encoding='utf-8'))
    nodes = []
    for n in tree['nodes']:
        nid = n['id']
        abil = n.get('abilities', [])
        nodes.append({
            'i': nid,
            'n': en.get('kindreds.node.%s.name' % nid, nid.split('.')[-1].replace('_', ' ')),
            'nr': ru.get('kindreds.node.%s.name' % nid, ''),
            'f': en.get('kindreds.node.%s.flavor' % nid, ''),
            'fr': ru.get('kindreds.node.%s.flavor' % nid, ''),
            't': n.get('tier', 0),
            'd': n['cost']['discipline'].split(':')[-1],
            'c': n['cost']['points'],
            'p': n.get('prereqs', []),
            'x': n.get('exclusive_group'),
            'deed': n.get('deed_advancement'),
            'e': [effect_text(a, 'en') for a in abil],
            'er': [effect_text(a, 'ru') for a in abil],
            'k': sorted({a.get('type') for a in abil}),
        })
    # renown deeds for this race
    deeds = []
    for df in sorted(glob.glob(RES + '/data/kindreds/advancement/renown/%s/*.json' % race)):
        d = json.load(open(df, encoding='utf-8'))
        # The deeds were rewritten per-race and moved to translate keys; reading 'text' off them
        # stopped working, and the explorer had been rebuilt from stale data ever since.
        disp = d['display']
        deeds.append({
            't': tr(disp['title'], en), 'd': tr(disp['description'], en),
            'tr': tr(disp['title'], ru), 'dr': tr(disp['description'], ru),
        })
    disciplines = []
    for n in nodes:
        if n['d'] not in disciplines:
            disciplines.append(n['d'])
    races[race] = {
        'name': RACE_EN[race], 'nameRu': RACE_RU[race],
        'blurb': BLURB_EN[race], 'blurbRu': BLURB_RU[race],
        'accent': '#%06X' % theme['primary_color'],
        'accent2': '#%06X' % theme['secondary_color'],
        'nodes': nodes, 'deeds': deeds, 'disciplines': disciplines,
        'points': sum(n['c'] for n in nodes),
        'effects': sum(len(n['e']) for n in nodes),
        'actives': sum(1 for n in nodes for k in [n['k']] if 'active' in k),
        'boons': sum(1 for n in nodes if 'contextual_boon' in n['k']),
    }

data = {
    'order': RACE_ORDER,
    'races': races,
    'disc': {'en': DISC_EN, 'ru': DISC_RU},
    'difficulty': [
        {'id': 'FIRESIDE', 'en': 'Fireside', 'ru': 'У очага', 'xp': 2.0, 'cap': 100, 'respec': 1,
         'death': 'keep everything', 'deathRu': 'ничего не теряется', 'scaling': False,
         'noteEn': 'Story pace. No cap at all - master your whole tree.',
         'noteRu': 'Для истории. Предела нет — можно освоить всё древо.'},
        {'id': 'ROAD', 'en': 'The Road', 'ru': 'Дорога', 'xp': 1.0, 'cap': 75, 'respec': 1,
         'death': 'keep everything', 'deathRu': 'ничего не теряется', 'scaling': False,
         'noteEn': 'The default. Master three or four lanes, never quite all.',
         'noteRu': 'По умолчанию. Три-четыре ветви, но никогда все.'},
        {'id': 'LONG_DEFEAT', 'en': 'The Long Defeat', 'ru': 'Долгое поражение', 'xp': 0.7, 'cap': 60,
         'respec': 4, 'death': 'lose unspent points', 'deathRu': 'теряются непотраченные очки',
         'scaling': True, 'noteEn': 'Committed. Real specialization pressure, tougher foes.',
         'noteRu': 'Всерьёз. Настоящее давление специализации, враги сильнее.'},
        {'id': 'DOOM', 'en': 'Doom', 'ru': 'Рок', 'xp': 0.45, 'cap': 45, 'respec': 8,
         'death': 'lose a quarter of your progress', 'deathRu': 'теряется четверть прогресса',
         'scaling': True, 'noteEn': 'Harsh. A deep specialist’s game.',
         'noteRu': 'Сурово. Игра узкого мастера.'},
    ],
}

json.dump(data, open(OUT, 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
print('wrote %s  (%.0f KB)' % (OUT, os.path.getsize(OUT) / 1024))
print('races: %d, nodes: %d' % (len(races), sum(len(r['nodes']) for r in races.values())))
for r in RACE_ORDER:
    x = races[r]
    print('  %-8s %-9s %3d nodes %4d pts %3d eff %2d act %2d boon %d deeds %s'
          % (r, x['accent'], len(x['nodes']), x['points'], x['effects'], x['actives'],
             x['boons'], len(x['deeds']), x['disciplines']))
