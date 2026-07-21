"""Race dossiers: the lore, and what it actually means with a controller in your hands.

The 'born with' lines are generated from the mod's birth_trait files so they can never
drift from the game; the prose is written.
"""
import json, glob, os

RES = 'C:/dev/minecraft_mods/new-mod/src/main/resources'
OUT = 'C:/Users/basma/AppData/Local/Temp/claude/C--Users-basma-Desktop-middle-earth/490ec692-6124-42a6-80c9-4c1ab8046bcf/scratchpad/kindreds-dossier.json'

ATTR_EN = {
    'minecraft:max_health': 'maximum health', 'minecraft:attack_damage': 'melee damage',
    'minecraft:attack_speed': 'attack speed', 'minecraft:movement_speed': 'movement speed',
    'minecraft:knockback_resistance': 'knockback resistance', 'minecraft:mining_efficiency': 'mining speed',
    'minecraft:burning_time': 'time spent burning', 'minecraft:safe_fall_distance': 'safe fall distance',
    'minecraft:luck': 'luck', 'minecraft:sneaking_speed': 'sneaking speed',
    'minecraft:movement_efficiency': 'footing on bad ground',
    'minecraft:block_interaction_range': 'block reach', 'minecraft:entity_interaction_range': 'attack reach',
    'middle-earth:detection_range': 'how far off mobs notice you',
    'middle-earth:climbing_strength': 'climbing', 'middle-earth:powdered_snow_immunity': 'immunity to freezing',
}
ATTR_RU = {
    'minecraft:max_health': 'к здоровью', 'minecraft:attack_damage': 'к урону в ближнем бою',
    'minecraft:attack_speed': 'к скорости атаки', 'minecraft:movement_speed': 'к скорости передвижения',
    'minecraft:knockback_resistance': 'к сопротивлению отбрасыванию', 'minecraft:mining_efficiency': 'к скорости добычи',
    'minecraft:burning_time': 'ко времени горения', 'minecraft:safe_fall_distance': 'к безопасной высоте падения',
    'minecraft:luck': 'к удаче', 'minecraft:sneaking_speed': 'к скорости в приседе',
    'minecraft:movement_efficiency': 'к устойчивости на плохой земле',
    'minecraft:block_interaction_range': 'к дальности до блоков', 'minecraft:entity_interaction_range': 'к дальности удара',
    'middle-earth:detection_range': 'к дальности, с которой вас замечают мобы',
    'middle-earth:climbing_strength': 'к лазанию', 'middle-earth:powdered_snow_immunity': 'к иммунитету к обморожению',
}
EFFECT_EN = {'night_vision': 'Night Vision', 'saturation': 'Saturation', 'regeneration': 'Regeneration',
             'haste': 'Haste', 'resistance': 'Resistance', 'weakness': 'Weakness', 'slowness': 'Slowness',
             'speed': 'Speed', 'strength': 'Strength'}
EFFECT_RU = {'night_vision': 'Ночное зрение', 'saturation': 'Насыщение', 'regeneration': 'Регенерация',
             'haste': 'Спешка', 'resistance': 'Сопротивление', 'weakness': 'Слабость', 'slowness': 'Медлительность',
             'speed': 'Скорость', 'strength': 'Сила'}
CTX_EN = {'starlight': 'under open night sky', 'daylight': 'in daylight', 'darkness': 'in darkness',
          'deep_dark': 'in the deep dark', 'underground': 'underground', 'low_health': 'below a third health',
          'dawn_dusk': 'at dawn and dusk'}
CTX_RU = {'starlight': 'под открытым ночным небом', 'daylight': 'при свете дня', 'darkness': 'во тьме',
          'deep_dark': 'в глубокой тьме', 'underground': 'под землёй', 'low_health': 'ниже трети здоровья',
          'dawn_dusk': 'на заре и в сумерках'}
PERK_EN = {'mining_fortune': 'ore sometimes yields extra', 'bane': 'heavier blows against your ancient foes',
           'heal_on_kill': 'a kill heals you', 'strike_effect': 'your hits sometimes poison what they land on'}
PERK_RU = {'mining_fortune': 'руда иногда даёт больше', 'bane': 'удары тяжелее против исконных врагов',
           'heal_on_kill': 'убийство лечит', 'strike_effect': 'удары иногда отравляют'}


def pct(v):
    return ('%+d%%' % round(v * 100))


def born_lines(race, lang):
    ru = lang == 'ru'
    A, E, C, P = (ATTR_RU, EFFECT_RU, CTX_RU, PERK_RU) if ru else (ATTR_EN, EFFECT_EN, CTX_EN, PERK_EN)
    # The same three groups the in-game traits page shows, so the book and the game agree on what
    # kind of fact each line is: what is always true of the body, what only wakes somewhere, and
    # what is carried as a burden.
    always, ctx, burden = [], [], []
    out = always
    data = json.load(open(RES + '/data/kindreds/kindreds/birth_trait/%s.json' % race, encoding='utf-8'))
    for a in data.get('traits', []):
        t = a.get('type')
        if t == 'attribute':
            # detection_range and burning_time read backwards: less of them is the gift, not the cost
            reversed_attr = a['attribute'] in ('middle-earth:detection_range', 'minecraft:burning_time')
            out = burden if (a.get('amount', 0) < 0 and not reversed_attr) else always
            name = A.get(a['attribute'], a['attribute'].split(':')[-1].replace('_', ' '))
            amt = a.get('amount', 0)
            if 'multiplied' in (a.get('operation') or ''):
                val = pct(amt)
            elif a['attribute'].endswith('max_health'):
                h = amt / 2
                val = ('%+g' % h) + (' сердца' if ru else (' heart' if abs(h) == 1 else ' hearts'))
            elif a['attribute'].endswith('detection_range'):
                val = pct(amt)
            elif a['attribute'].endswith('climbing_strength'):
                out.append('лазает по отвесному камню' if ru else 'climbs sheer stone')
                continue
            else:
                val = '%+g' % amt
            out.append(val + ' ' + name)
        elif t == 'status_effect':
            out = always
            eff = E.get(a['effect'].split(':')[-1], a['effect'].split(':')[-1])
            out.append((eff + (', всегда' if ru else ', always on')))
        elif t == 'perk':
            out = always
            out.append(P.get(a['perk'].split(':')[-1], a['perk'].split(':')[-1].replace('_', ' ')))
        elif t in ('contextual_boon', 'curse'):
            out = ctx
            when = C.get(a.get('when'), a.get('when'))
            inner = a.get('effect', {})
            if inner.get('type') == 'status_effect':
                what = E.get(inner['effect'].split(':')[-1], inner['effect'].split(':')[-1])
            else:
                amt = inner.get('amount', 0)
                nm = A.get(inner.get('attribute', ''), (inner.get('attribute') or '').split(':')[-1])
                what = (pct(amt) if 'multiplied' in (inner.get('operation') or '') else '%+g' % amt) + ' ' + nm
            mark = ('✦ ' if t == 'contextual_boon' else '✖ ')
            out.append(mark + when + ': ' + what)
    return {'always': always, 'ctx': ctx, 'burden': burden}


# hand-written: the lore, and what it means to play
D = {
 'elf': {
  'loreEn': "The Firstborn woke under stars before the Sun was made, and never quite left that hour behind. They do not sleep as Men do, they do not weary on the road, and their sight and hearing shame every other kindred. What an Elf makes, an Elf sings over — and the song is part of the making.",
  'playEn': "The most ability-driven kindred in the mod: nine distinct actives, more than anyone else, from the Galadhrim volley to the Phial of Galadriel. You are fast, quiet and fragile-ish — a hit-and-run archer who fights best outdoors at night, where starlight quietly hands you regeneration and speed. Take the Song branch if you play with others; it heals and binds.",
  'loreRu': "Перворождённые пробудились под звёздами прежде, чем создали Солнце, и тот час остался в них навсегда. Они не спят, как люди, не устают в пути, а их зрение и слух посрамляют любой другой народ. Что эльф делает — над тем эльф поёт, и песнь есть часть работы.",
  'playRu': "Самый «активный» народ мода: девять разных умений — больше, чем у кого-либо, — от залпа галадрим до Фиала Галадриэли. Вы быстры, тихи и не слишком выносливы: лучник для налётов, лучше всего сражающийся ночью под открытым небом, где звёздный свет тихо даёт регенерацию и скорость. Ветвь Песни — для игры в компании: она лечит и связывает врагов.",
  'strongEn': ['Nine active abilities — the widest toolkit', 'Fast, quiet, and hard to notice',
               'Grows stronger under open night sky', 'Falls 8 blocks further without harm'],
  'weakEn': ['Weakness in the deep dark — the one place they falter', 'Fewest passive effects per point'],
  'strongRu': ['Девять активных умений — самый широкий набор', 'Быстры, тихи и незаметны',
               'Сильнее под открытым ночным небом', 'Падают на 8 блоков дальше без вреда'],
  'weakRu': ['Слабость в глубокой тьме — единственное место, где они сдают', 'Меньше всего пассивных эффектов на очко'],
  'sig': [['Galadhrim Volley', 'Залп галадрим'], ['Phial of Galadriel', 'Фиал Галадриэли'],
          ['Song of Lúthien', 'Песнь Лутиэн']],
 },
 'dwarf': {
  'loreEn': "Aulë made them in secret, impatient for children of his own, and Ilúvatar let them wake. They are stone-hard and stone-stubborn: seven fathers, seven halls, and a memory that outlasts kingdoms. A Dwarf's craft is his name, and Khazad-dûm was the greatest delving any people ever dared.",
  'playEn': "Four branches instead of five, each dug deeper. You are the tank and the miner: two extra hearts, real knockback resistance, faster mining, ore that sometimes yields double, and fire that burns you 60% less. Underground you gain Haste and Resistance simply for being where you belong. Uniquely, Dwarves repair the gear they carry — nobody else mends anything.",
  'loreRu': "Аулэ создал их втайне, не дождавшись собственных детей, и Илуватар дал им пробудиться. Они твёрды и упрямы, как камень: семь отцов, семь чертогов и память, что переживает королевства. Ремесло гнома — его имя, а Казад-дум был величайшим подземельем, на какое отважился хоть один народ.",
  'playRu': "Четыре ветви вместо пяти, но каждая глубже. Вы — танк и шахтёр: два лишних сердца, настоящее сопротивление отбрасыванию, быстрая добыча, руда, что иногда даёт вдвое, и огонь, жгущий на 60% меньше. Под землёй вы получаете Спешку и Сопротивление просто за то, что вы там, где вам место. И только гномы чинят своё снаряжение.",
  'strongEn': ['Toughest kindred: extra hearts, knockback resistance, 60% less burning',
               'Haste and Resistance underground, always', 'Ore sometimes yields double',
               'The only kindred that repairs its own gear'],
  'weakEn': ['Four branches, so a narrower spread of skills', 'Slow — no movement bonuses at all'],
  'strongRu': ['Самый крепкий народ: лишние сердца, сопротивление отбрасыванию, −60% времени горения',
               'Спешка и Сопротивление под землёй, всегда', 'Руда иногда даёт вдвое',
               'Единственный народ, чинящий своё снаряжение'],
  'weakRu': ['Четыре ветви — уже разброс умений', 'Медлительны: никаких бонусов к скорости'],
  'sig': [["Durin's Wrath", 'Гнев Дурина'], ['Khazad-dûm Reborn', 'Возрождённый Казад-дум'],
          ["Aulë's Legacy", 'Наследие Аулэ']],
 },
 'human': {
  'loreEn': "The Second-born are given a strange gift: they die. Everything they build is built against that clock, which is why Men raise cities, muster armies and crown kings while the elder kindreds remember. Gondor, Rohan and Dale — sea-craft, horse-craft and the bow that felled a dragon.",
  'playEn': "The broadest kindred and the most forgiving: two extra hearts, +15% melee damage, half a block more reach with both hand and blade, and sure footing on ground that slows everyone else. Men come alive when it goes badly — below a third health they gain damage, Resistance and Speed. The Leadership branch is the only one built around other players.",
  'loreRu': "Пришедшим следом дан странный дар: они умирают. Всё, что они строят, строится наперекор этим часам — потому люди возводят города, собирают войска и венчают королей, пока старшие народы помнят. Гондор, Рохан и Дейл: море, кони и лук, сваливший дракона.",
  'playRu': "Самый широкий и самый снисходительный народ: два лишних сердца, +15% урона, на полблока больше дальности и рукой, и клинком, и твёрдый шаг там, где вязнут другие. Люди оживают, когда всё плохо: ниже трети здоровья — урон, Сопротивление и Скорость. Ветвь Предводительства — единственная, построенная вокруг других игроков.",
  'strongEn': ['Strong at everything, weak at nothing', 'Longer reach on blocks and on foes',
               'Fights hardest when nearly dead', 'Leadership: the only branch that buffs your companions'],
  'weakEn': ['No night vision, no special sight', 'Master of none — no single overwhelming strength'],
  'strongRu': ['Сильны во всём, слабы ни в чём', 'Больше дальность и по блокам, и по врагам',
               'Дерутся яростнее всего на грани смерти', 'Предводительство — единственная ветвь, усиливающая спутников'],
  'weakRu': ['Ни ночного зрения, ни особого чутья', 'Ни в чём не первые'],
  'sig': [['Andúril', 'Андуриль'], ['Ride of the Rohirrim', 'Скачка рохиррим'],
          ['Hands of the King', 'Руки короля']],
 },
 'hobbit': {
  'loreEn': "No one expects anything of Hobbits, which is precisely why they win. They are small, well-fed, unfashionably sensible, and can vanish behind a hedge before a big person finishes turning round. Bilbo walked out of Bag End without a handkerchief and came home with a dragon's ransom.",
  'playEn': "The stealth kindred, and the luckiest folk in Middle-earth: +3 luck on every drop and catch, faster sneaking, and mobs notice you from far closer. Their stealth branch ends at the floor of what the game allows — nothing goes as unseen as a Hobbit. They throw stones rather than swing swords, and the Song branch feeds the whole party.",
  'loreRu': "От хоббитов никто ничего не ждёт — именно поэтому они побеждают. Они малы, сыты, немодно рассудительны и исчезают за изгородью прежде, чем большой народ успеет обернуться. Бильбо вышел из Бэг-Энда без носового платка, а вернулся с драконьим выкупом.",
  'playRu': "Народ скрытности и самый удачливый в Средиземье: +3 к удаче на каждый дроп и улов, быстрый присед, и мобы замечают вас с куда меньшего расстояния. Их ветвь скрытности упирается в предел, дозволенный игрой: никто не ходит так незаметно, как хоббит. Они мечут камни, а не машут мечом, а ветвь Песни кормит весь отряд.",
  'strongEn': ['The least detectable kindred in the mod, by design', '+3 luck: better loot, better fishing',
               'Second Breakfast — food goes further', 'Sling-stones: a ranged attack with no arrows'],
  'weakEn': ['No night vision', 'Small and soft in a straight fight'],
  'strongRu': ['Самый незаметный народ мода — так и задумано', '+3 к удаче: лучше добыча и рыбалка',
               'Второй завтрак: еды хватает надолго', 'Праща: дальний бой без стрел'],
  'weakRu': ['Нет ночного зрения', 'Малы и мягки в честной схватке'],
  'sig': [['Vanish', 'Исчезновение'], ['Sling-stones', 'Праща'], ['Good Cheer', 'Доброе застолье']],
 },
 'uruk': {
  'loreEn': "Saruman's answer to the failures of Mordor: orcs that do not cower from the Sun. Bred, not born, in the pits beneath Isengard — taller, black-blooded, and utterly without the whining cowardice of their lesser kin. They march for days and they do not stop.",
  'playEn': "The heavy infantry of the mod, and the only orc-kind that thrives at noon: three extra hearts, +30% melee damage, knockback resistance, and a kill that heals three health. Daylight makes them stronger, not weaker. The price is speed — their swing is 15% slower — and their Unyielding perk makes them impossible to slow or tire.",
  'loreRu': "Ответ Сарумана на неудачи Мордора: орки, не прячущиеся от Солнца. Выведены, а не рождены, в ямах под Изенгардом — выше ростом, чернокровные и вовсе лишённые трусливого нытья младшей родни. Они идут сутками и не останавливаются.",
  'playRu': "Тяжёлая пехота мода и единственный орочий род, что расцветает в полдень: три лишних сердца, +30% урона, сопротивление отбрасыванию и лечение на 3 за убийство. Дневной свет делает их сильнее, а не слабее. Цена — скорость: замах на 15% медленнее; зато Несгибаемость не даёт их ни замедлить, ни утомить.",
  'strongEn': ['+30% melee damage and three extra hearts', 'Stronger in daylight, unlike every other orc',
               'Kills heal you', 'Unyielding: cannot be slowed or tired'],
  'weakEn': ['15% slower attack speed', 'No stealth to speak of'],
  'strongRu': ['+30% урона и три лишних сердца', 'Сильнее при свете дня, в отличие от прочих орков',
               'Убийства лечат', 'Несгибаемость: их нельзя замедлить или утомить'],
  'weakRu': ['Скорость атаки ниже на 15%', 'Скрытности почти нет'],
  'sig': [['Savage Swing', 'Дикий взмах'], ['Blasting Fire', 'Взрывной огонь'], ['Warg Pack', 'Стая варгов']],
 },
 'orc': {
  'loreEn': "Bred in mockery of the Elves, in the pits of Utumno, and never forgiven for it. Orcs of Mordor are bold in the dark and wretched under the Sun; they fight in numbers because alone they are nothing, and the Eye is always watching whether they win or run.",
  'playEn': "A darkness build: night vision from birth, +25% melee damage, a kill that heals you, and a heavy damage bonus while unlit. Step into daylight and it inverts — you lose damage and gain Slowness, so torches and timing matter more than for anyone else. The Shadow branch is the deepest well of conditional power in the mod.",
  'loreRu': "Выведены в насмешку над эльфами, в ямах Утумно, и этого им не простили. Орки Мордора дерзки во тьме и жалки под Солнцем; они бьются числом, ибо поодиночке они ничто, а Око следит — победят они или побегут.",
  'playRu': "Сборка вокруг тьмы: ночное зрение с рождения, +25% урона, лечение за убийство и крупная прибавка к урону без света. Шагните на дневной свет — и всё переворачивается: урон падает, приходит Медлительность. Факелы и время суток важны для вас, как ни для кого. Ветвь Тени — самый глубокий источник условной силы в моде.",
  'strongEn': ['+25% melee damage from birth, more in the dark', 'Night vision, always',
               'Kills heal you', 'The most conditional effects of any kindred'],
  'weakEn': ['Daylight is a real penalty: less damage, Slowness', 'Fragile compared to Uruk-hai'],
  'strongRu': ['+25% урона с рождения и больше во тьме', 'Ночное зрение, всегда',
               'Убийства лечат', 'Больше всего условных эффектов'],
  'weakRu': ['Дневной свет — настоящий штраф: меньше урона, Медлительность', 'Хрупче урук-хай'],
  'sig': [['Blood Frenzy', 'Кровавое безумие'], ['War Horn', 'Боевой рог'], ['Warg Pack', 'Стая варгов']],
 },
 'snaga': {
  'loreEn': "Snaga means slave. They are the smallest orcs, the ones driven with whips at the back of the column, kicked by their own captains and first to run. They survive by being not worth killing — and by knifing whatever turns its back on them.",
  'playEn': "A glass cannon that plays dirty. No bonus health, no armour — instead, poison on roughly a third of your hits, near-invisibility, and a low-health lane deeper than any other in the mod: below a third health you stack Strength, Speed, armour, faster swings and raw damage. Snaga are at their most dangerous when they should already be dead.",
  'loreRu': "«Снага» значит раб. Это мельчайшие орки — те, кого гонят плетьми в хвосте колонны, пинают собственные же начальники и кто первым бежит. Они выживают тем, что их не стоит убивать, — и тем, что бьют ножом всякого, кто повернулся спиной.",
  'playRu': "Стеклянная пушка, играющая грязно. Ни лишнего здоровья, ни брони — зато яд примерно на трети ударов, почти полная незаметность и самая глубокая ветвь «на грани» во всём моде: ниже трети здоровья копятся Сила, Скорость, броня, ускоренные замахи и чистый урон. Снага опаснее всего тогда, когда должен был бы уже умереть.",
  'strongEn': ['Poison on roughly a third of your strikes', 'Nearly invisible: mobs notice you very late',
               'The deepest low-health lane in the mod', 'Night vision, always'],
  'weakEn': ['No bonus health or armour whatsoever', 'Daylight: less damage and Slowness'],
  'strongRu': ['Яд примерно на трети ударов', 'Почти невидимы: мобы замечают крайне поздно',
               'Самая глубокая ветвь «на грани смерти»', 'Ночное зрение, всегда'],
  'weakRu': ['Ни лишнего здоровья, ни брони', 'Дневной свет: меньше урона и Медлительность'],
  'sig': [['Skulk', 'Крадучись'], ['Orc-draught', 'Орочье пойло'], ['Blood Frenzy', 'Кровавое безумие']],
 },
 'goblin': {
  'loreEn': "The folk of Goblin-town do not build, they burrow — a city of ramshackle bridges over a black chasm, lit by whatever they stole. They are clever with wheels and engines and explosions, they climb like spiders, and they sing dreadful songs about doing unpleasant things to prisoners.",
  'playEn': "The only kindred that climbs sheer stone: your climbing strength is set so high you go up walls where everyone else needs a ladder. Add night vision, near-invisibility and a smithing branch built on bombs — the goblin bomb and cluster bomb are yours alone. Daylight punishes you as it does all orc-kind, but you were never going up there anyway.",
  'loreRu': "Народ Гоблин-тауна не строит — он роет: город шатких мостов над чёрной пропастью, освещённый краденым. Они ловки с колёсами, механизмами и взрывами, лазают, как пауки, и поют мерзкие песни о том, что делают с пленниками.",
  'playRu': "Единственный народ, взбирающийся по отвесному камню: сила лазания задана так высоко, что вы идёте по стенам там, где прочим нужна лестница. Прибавьте ночное зрение, почти полную незаметность и кузнечную ветвь, построенную на бомбах: гоблинская бомба и кассетная — только ваши. Дневной свет карает вас, как всю орочью родню, но вы туда и не собирались.",
  'strongEn': ['Climbs sheer walls — unique in the mod', 'Bombs: goblin bomb and cluster bomb',
               'Night vision and near-invisibility', 'Immune to freezing'],
  'weakEn': ['Daylight: less damage and Slowness', 'Fewest active abilities of any kindred'],
  'strongRu': ['Лазают по отвесным стенам — единственные в моде', 'Бомбы: гоблинская и кассетная',
               'Ночное зрение и почти полная незаметность', 'Иммунитет к обморожению'],
  'weakRu': ['Дневной свет: меньше урона и Медлительность', 'Меньше всего активных умений'],
  'sig': [['Goblin Bomb', 'Гоблинская бомба'], ['Cluster Bomb', 'Кассетная бомба'], ['Skulk', 'Крадучись']],
 },
}

out = {}
for race, d in D.items():
    out[race] = {
        'lore': {'en': d['loreEn'], 'ru': d['loreRu']},
        'play': {'en': d['playEn'], 'ru': d['playRu']},
        'strong': {'en': d['strongEn'], 'ru': d['strongRu']},
        'weak': {'en': d['weakEn'], 'ru': d['weakRu']},
        'sig': {'en': [s[0] for s in d['sig']], 'ru': [s[1] for s in d['sig']]},
        'born': {'en': born_lines(race, 'en'), 'ru': born_lines(race, 'ru')},
    }

json.dump(out, open(OUT, 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
print('wrote dossiers for %d kindreds (%.0f KB)' % (len(out), os.path.getsize(OUT) / 1024))
for r in ['elf', 'goblin']:
    print('\n%s born-with (generated from birth_trait json):' % r)
    for group, lines in out[r]['born']['en'].items():
        print('  [%s]' % group)
        for l in lines:
            print('   ', l)
