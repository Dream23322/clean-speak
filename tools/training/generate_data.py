#!/usr/bin/env python3
"""
Generate a larger, family-labelled starter corpus for the CleanSpeak classifier.

Why templates instead of just more AI prose?
  * We can control labels and hard negatives.
  * Each template gets a family id, so train/test splits can be grouped by family
    instead of leaking paraphrases across the split.
  * Actual slurs are not generated or committed. {SLUR} is expanded only in the
    private training copy (data/lexicon.local.json).

The output is data/generated.jsonl. It is merged by prepare_dataset.py.
"""

from __future__ import annotations

import argparse
import json
import random
from pathlib import Path

# target_group, singular identity phrase, plural group phrase, identity phrase
GROUPS = [
    ("gender_identity", "trans", "trans people", "being trans"),
    ("gender_identity", "nonbinary", "nonbinary people", "being nonbinary"),
    ("gender_identity", "transgender", "transgender people", "being transgender"),
    ("sexual_orientation", "gay", "gay people", "being gay"),
    ("sexual_orientation", "lesbian", "lesbian players", "being a lesbian"),
    ("sexual_orientation", "bisexual", "bisexual people", "being bisexual"),
    ("sexual_orientation", "queer", "queer people", "being queer"),
    ("race_ethnicity", "Black", "Black players", "being Black"),
    ("race_ethnicity", "Asian", "Asian players", "being Asian"),
    ("race_ethnicity", "Latino", "Latino players", "being Latino"),
    ("race_ethnicity", "Arab", "Arab players", "being Arab"),
    ("religion", "Muslim", "Muslim players", "being Muslim"),
    ("religion", "Jewish", "Jewish players", "being Jewish"),
    ("religion", "Hindu", "Hindu players", "being Hindu"),
    ("religion", "Christian", "Christian players", "being Christian"),
    ("disability", "disabled", "disabled players", "being disabled"),
    ("disability", "autistic", "autistic players", "being autistic"),
    ("disability", "deaf", "deaf players", "being deaf"),
    ("nationality", "Mexican", "Mexican players", "being Mexican"),
    ("nationality", "Indian", "Indian players", "being Indian"),
    ("nationality", "Nigerian", "Nigerian players", "being Nigerian"),
    ("nationality", "Chinese", "Chinese players", "being Chinese"),
    ("nationality", "American", "American players", "being American"),
    ("other", "weird", "weird players", "being weird"),
]

TARGETS = ["ExamplePlayer", "ThatPlayer", "this player", "that player"]
NEGATIVE = ["trash", "disgusting", "worthless", "a joke", "inferior", "freaks", "pathetic"]


def add(rows, seen, family, category, label, text, target_group="none",
        targeted=False, hard_negative=False, note=""):
    text = " ".join(text.split()).strip()
    key = text.lower()
    if not text or key in seen:
        return
    seen.add(key)
    rows.append({
        "text": text,
        "channel": "public_chat",
        "label": label,
        "category": category,
        "targeted": targeted,
        "target_group": target_group,
        "hard_negative": hard_negative,
        "language": "en",
        "family": family,
        "note": note or family,
        "source": "generated",
    })


def generate(seed: int) -> list[dict]:
    rnd = random.Random(seed)
    rows: list[dict] = []
    seen: set[str] = set()

    # Direct targeted hate: second person.
    direct = [
        ("hate.direct-01", "you are a {slur}", "directed slur"),
        ("hate.direct-02", "you are such a {slur}", "directed slur"),
        ("hate.direct-03", "you are a weird {slur}", "directed slur"),
        ("hate.direct-04", "you are a {negative} {slur}", "directed slur"),
        ("hate.direct-05", "shut up, you {slur}", "directed slur"),
        ("hate.direct-06", "get out, you {slur}", "directed slur"),
        ("hate.direct-07", "you are a walking {slur}", "directed slur"),
        ("hate.direct-08", "nobody wants you here, {slur}", "directed slur"),
        ("hate.direct-09", "you're a {slur}, nobody likes you", "directed slur"),
        ("hate.direct-10", "you're just a weird {slur}", "directed slur"),
        ("hate.direct-11", "people like you are {negative} {slur}s", "group slur"),
        ("hate.direct-12", "your kind doesn't belong here", "exclusion"),
        ("hate.direct-13", "go away, {slur}", "directed slur"),
        ("hate.direct-14", "you don't belong here, {slur}", "directed slur"),
        ("hate.direct-15", "you are only bad because you're {identity}", "identity insult"),
        ("hate.direct-16", "your {identity} makes you {negative}", "identity insult"),
        ("hate.direct-17", "your {identity} is disgusting", "identity insult"),
        ("hate.direct-18", "you're a freak for being {identity}", "identity insult"),
        ("hate.direct-19", "being {identity} makes you a joke", "identity insult"),
        ("hate.direct-20", "you are a {slur} and your whole family is too", "family slur"),
        ("hate.direct-21", "get lost, {slur}", "directed slur"),
        ("hate.direct-22", "we don't need people like you here, {slur}", "exclusion"),
        ("hate.direct-23", "you're the reason {group_plural} shouldn't be allowed", "group exclusion"),
        ("hate.direct-24", "I hate people like you, {slur}", "directed hate"),
    ]
    for family, template, note in direct:
        for group, identity, plural, identity_phrase in GROUPS:
            if "{identity}" in template:
                text = template.format(identity=identity_phrase.replace("being ", ""), negative=rnd.choice(NEGATIVE))
            elif "{group_plural}" in template:
                text = template.format(group_plural=plural)
            else:
                text = template.format(negative=rnd.choice(NEGATIVE), slur="{SLUR}")
            # Direct second-person identity templates only make sense for a few
            # identities; still valid as explicit identity attacks.
            add(rows, seen, family + "." + group, "TARGETED_HATE", "REPHRASE", text,
                group, True, False, note)
            # A small number of third-person variants.
            if "{group_plural}" not in template:
                third = text.replace("you are", "ExamplePlayer is", 1).replace("you're", "ExamplePlayer is", 1)
                third = third.replace("your ", "ExamplePlayer's ", 1)
                third = third.replace(" you ", " ExamplePlayer ", 1)
                add(rows, seen, family + ".third." + group, "TARGETED_HATE", "REPHRASE", third,
                    group, True, False, note)

    # Third-person targeted abuse.
    third = [
        ("hate.third-01", "{target} is a {slur}", "third-party slur"),
        ("hate.third-02", "{target} is such a {slur}", "third-party slur"),
        ("hate.third-03", "{target} is a weird {slur}", "third-party slur"),
        ("hate.third-04", "don't team with {target}, they're a {slur}", "third-party slur"),
        ("hate.third-05", "{target} is a {negative} {slur}", "third-party slur"),
        ("hate.third-06", "nobody invite {target}, they're {slur}", "third-party exclusion"),
        ("hate.third-07", "{target} is a {slur} and their whole clan is too", "third-party slur"),
        ("hate.third-08", "{target} should leave because they're {identity}", "identity exclusion"),
        ("hate.third-09", "{target} is a freak for being {identity}", "identity insult"),
        ("hate.third-10", "{target} is only bad because of their {identity}", "identity insult"),
    ]
    for family, template, note in third:
        for group, identity, plural, identity_phrase in GROUPS:
            text = template.format(
                target=rnd.choice(TARGETS), slur="{SLUR}",
                negative=rnd.choice(NEGATIVE), identity=identity_phrase.replace("being ", "")
            )
            add(rows, seen, family + "." + group, "TARGETED_HATE", "REPHRASE", text,
                group, True, False, note)

    # Group-directed hate.
    group_templates = [
        ("hate.group-01", "all {group_plural} should leave this server", "group exclusion"),
        ("hate.group-02", "we don't want {group_plural} here", "group exclusion"),
        ("hate.group-03", "{group_plural} are ruining this server", "group hate"),
        ("hate.group-04", "{group_plural} are {negative}", "group hate"),
        ("hate.group-05", "{group_plural} shouldn't be allowed to play", "group exclusion"),
        ("hate.group-06", "I hate {group_plural}", "group hate"),
        ("hate.group-07", "only {group_plural} cheat", "group generalization"),
        ("hate.group-08", "{group_plural} don't belong here", "group exclusion"),
        ("hate.group-09", "{group_plural} are inferior", "group hate"),
        ("hate.group-10", "keep {group_plural} out of this community", "group exclusion"),
    ]
    for family, template, note in group_templates:
        for group, identity, plural, identity_phrase in GROUPS:
            add(rows, seen, family + "." + group, "TARGETED_HATE", "REPHRASE",
                template.format(group_plural=plural, negative=rnd.choice(NEGATIVE)),
                group, True, False, note)

    # General targeted harassment without protected-class hate. Still targeted,
    # but the user's policy wants ordinary shit talk to remain ALLOW. These are
    # deliberately harsher than ordinary trash talk.
    harassment = [
        ("harass-01", "nobody wants you here, leave", "targeted exclusion"),
        ("harass-02", "everyone hates you", "targeted degradation"),
        ("harass-03", "you are the reason this server is dying", "targeted degradation"),
        ("harass-04", "kill yourself", "self-harm instruction"),
        ("harass-05", "go die", "self-harm/insult"),
        ("harass-06", "I hope you get hurt", "targeted harm wish"),
        ("harass-07", "you're worthless and everyone knows it", "targeted degradation"),
        ("harass-08", "stop talking, nobody cares about you", "targeted exclusion"),
        ("harass-09", "you should be ashamed of existing", "targeted degradation"),
        ("harass-10", "get off this server and never come back", "targeted exclusion"),
    ]
    for family, text, note in harassment:
        add(rows, seen, family, "TARGETED_HARASSMENT", "REPHRASE", text,
            "other", True, False, note)

    # Direct real-world threats (not game threats).
    threats = [
        ("threat-01", "I will kill you", "direct threat"),
        ("threat-02", "I'm going to find you and hurt you", "direct threat"),
        ("threat-03", "I know where you live", "real-world threat"),
        ("threat-04", "I'll break your jaw next time I see you", "physical threat"),
        ("threat-05", "I'm coming to your house to hurt you", "real-world threat"),
        ("threat-06", "someone should shoot ExamplePlayer", "incitement"),
        ("threat-07", "I will dox you and your family", "doxxing threat"),
        ("threat-08", "I'll swat you", "swatting threat"),
        ("threat-09", "keep talking and I'll stab you", "direct threat"),
        ("threat-10", "I will hurt your family", "family threat"),
        ("threat-11", "I'll beat you up after school", "physical threat"),
        ("threat-12", "you're dead when I find you", "death threat"),
    ]
    for family, text, note in threats:
        add(rows, seen, family, "THREAT", "BLOCK", text, "none", True, False, note)

    # Ordinary shit talk and profanity: explicitly ALLOW. This is the critical
    # hard-negative side of the user's policy.
    general = [
        "you're trash at PvP, uninstall",
        "you suck at building, get good",
        "ez noob, try again",
        "that build is awful",
        "your redstone is a mess",
        "nice aim, did you close your eyes?",
        "you're so bad at this game",
        "stop griefing, that's annoying",
        "what a noob",
        "you lost to a wooden sword",
        "fuck this lag",
        "this server is dogshit today",
        "I'm so done with this game",
        "that was a terrible play",
        "you're playing like a bot",
        "get good, seriously",
        "I'll destroy your base next round",
        "I'm going to beat you in the next match",
        "I'll steal your diamonds",
        "your farm is ugly",
        "stop stealing my loot",
        "you're a weirdo but that's fine",
        "this update is trash",
        "I hate this map",
        "my team is useless",
        "why are you so slow",
        "that fight was pathetic",
        "you guys are terrible at this",
        "I'm going to log off, this is boring",
        "wtf was that",
        "lol you fell in lava",
        "nice one, genius",
        "bro that was dumb",
        "you missed every shot",
        "this is the worst team ever",
        "you're cooked next round",
        "I'm going to win this time",
        "your defense is garbage",
    ]
    for index, text in enumerate(general):
        add(rows, seen, f"general.{index}", "NONE", "ALLOW", text,
            "none", False, True, "ordinary shit talk or profanity")

    # Neutral identity discussion and questions. These are hard negatives.
    neutral = [
        ("trans people are welcome on this server", "gender_identity"),
        ("trans people exist and play Minecraft too", "gender_identity"),
        ("I'm trans and I've been playing here for years", "gender_identity"),
        ("my friend is trans and she's awesome", "gender_identity"),
        ("is being trans okay on this server?", "gender_identity"),
        ("what does nonbinary mean?", "gender_identity"),
        ("trans is an identity word, not an insult", "gender_identity"),
        ("gay players are welcome here", "sexual_orientation"),
        ("my cousin is gay and he's great at building", "sexual_orientation"),
        ("I'm gay and I'm still better at bedwars than you", "sexual_orientation"),
        ("is being gay allowed here?", "sexual_orientation"),
        ("my sister is a lesbian and she plays too", "sexual_orientation"),
        ("Muslim players are part of this community", "religion"),
        ("my friend is Muslim and she runs the best shop", "religion"),
        ("the Jewish holiday is tomorrow so I'll be offline", "religion"),
        ("is this server welcoming to Hindu players?", "religion"),
        ("I'm Christian but I don't mind other beliefs", "religion"),
        ("players of all religions are welcome", "religion"),
        ("I'm Black and I've been playing here since 2019", "race_ethnicity"),
        ("my teammates are Asian and they're cracked", "race_ethnicity"),
        ("players from different countries can join the same team", "nationality"),
        ("my friend is from Mexico and she's a great builder", "nationality"),
        ("what's the difference between ethnicity and nationality?", "race_ethnicity"),
        ("disabled players are welcome here", "disability"),
        ("my brother is autistic and he loves this server", "disability"),
        ("is the server accessible for deaf players?", "disability"),
        ("that joke was offensive to disabled people", "disability"),
        ("people can be weird and still be good teammates", "other"),
        ("I'm not like other players, I just build farms", "other"),
    ]
    for index, (text, group) in enumerate(neutral):
        add(rows, seen, f"neutral.{index}", "NONE", "ALLOW", text,
            group, False, True, "neutral identity discussion")

    # Mild identity-based trash talk that must remain ALLOW because it does not
    # attack a protected class.
    identity_hard = [
        ("you're trash at PvP, but your skin is cool", "none"),
        ("your build is bad, the colors are nice though", "none"),
        ("I'm gay and I'm still better at bedwars than you", "sexual_orientation"),
        ("my trans friend carried our team", "gender_identity"),
        ("our Muslim teammate is carrying the round", "religion"),
        ("my Black friend built this castle", "race_ethnicity"),
        ("I'm disabled but I can still beat you in PvP", "disability"),
        ("this map is so gay, the colors are everywhere", "sexual_orientation"),
    ]
    for index, (text, group) in enumerate(identity_hard):
        add(rows, seen, f"identity-hard.{index}", "NONE", "ALLOW", text,
            group, False, True, "identity mention without targeted hate")


    # Wider real-world threat set. Keep the language non-graphic but explicit
    # enough that the classifier learns intent rather than one memorized phrase.
    threat_templates = [
        ("threat.more.01", "I will find you and hurt you"),
        ("threat.more.02", "I know where you live"),
        ("threat.more.03", "I'm coming to your house"),
        ("threat.more.04", "I will dox you"),
        ("threat.more.05", "I will swat you"),
        ("threat.more.06", "I'll break your legs"),
        ("threat.more.07", "I'll break your jaw"),
        ("threat.more.08", "I will hurt your family"),
        ("threat.more.09", "you're dead when I see you"),
        ("threat.more.10", "I'm going to make you regret this"),
        ("threat.more.11", "I will attack you in real life"),
        ("threat.more.12", "I'm going to find your address"),
        ("threat.more.13", "I'll shoot you"),
        ("threat.more.14", "I'll stab you"),
        ("threat.more.15", "I will end you"),
        ("threat.more.16", "I'm going to hurt you after the match"),
        ("threat.more.17", "watch your back in real life"),
        ("threat.more.18", "I know your school"),
        ("threat.more.19", "I'll get you outside the game"),
        ("threat.more.20", "you should be scared to leave your house"),
    ]
    for family, text in threat_templates:
        for target in ("you", "ExamplePlayer"):
            value = text.replace("you", target) if target != "you" else text
            add(rows, seen, family + "." + target, "THREAT", "BLOCK", value,
                "none", True, False, "real-world threat")

    harassment_templates = [
        ("harass.more.01", "nobody likes you"),
        ("harass.more.02", "everyone hates you"),
        ("harass.more.03", "you're worthless"),
        ("harass.more.04", "you're a waste of space"),
        ("harass.more.05", "we're all laughing at you"),
        ("harass.more.06", "get off this server and never come back"),
        ("harass.more.07", "you ruin every game you join"),
        ("harass.more.08", "you don't deserve friends"),
        ("harass.more.09", "you should be ashamed of yourself"),
        ("harass.more.10", "I hope you fail at everything"),
        ("harass.more.11", "shut up forever"),
        ("harass.more.12", "you're the worst person here"),
        ("harass.more.13", "nobody invited you"),
        ("harass.more.14", "stop talking, everyone is tired of you"),
        ("harass.more.15", "you're pathetic and everyone knows it"),
    ]
    for family, text in harassment_templates:
        add(rows, seen, family, "TARGETED_HARASSMENT", "REPHRASE", text,
            "other", True, False, "targeted degradation")

    # A few identity-based hate patterns that do not use a slur token.
    for family, template in [
        ("hate.identity.more.01", "I hate {plural}"),
        ("hate.identity.more.02", "{plural} are ruining the server"),
        ("hate.identity.more.03", "{plural} don't belong here"),
        ("hate.identity.more.04", "{plural} are a disease"),
        ("hate.identity.more.05", "{plural} should be banned"),
        ("hate.identity.more.06", "we need fewer {plural} here"),
        ("hate.identity.more.07", "{plural} are not welcome"),
        ("hate.identity.more.08", "{plural} are inferior"),
        ("hate.identity.more.09", "{plural} are a joke"),
        ("hate.identity.more.10", "I can't stand {plural}"),
    ]:
        for group, identity, plural, identity_phrase in GROUPS:
            add(rows, seen, family + "." + group, "TARGETED_HATE", "REPHRASE",
                template.format(plural=plural), group, True, False,
                "targeted identity-based hate")

    # Spam, flooding, scam and advertising. Links use example.com only.
    spam = [
        ("spam-01", "BUY NOW BUY NOW BUY NOW BUY NOW", "flooding"),
        ("spam-02", "join join join join join join", "flooding"),
        ("spam-03", "free rank at example.com", "advertising"),
        ("spam-04", "free nitro at example.com", "scam"),
        ("spam-05", "claim your free reward at example.com", "scam"),
        ("spam-06", "verify your Microsoft account at example.com", "scam"),
        ("spam-07", "steam gift at example.com", "scam"),
        ("spam-08", "join my server at example.com", "advertising"),
        ("spam-09", "check this link at example.com", "link spam"),
        ("spam-10", "free robux at example.com", "scam"),
        ("spam-11", "I said it already, stop repeating", "duplicate context"),
        ("spam-12", "AAAAAAAAAAAAAAAAAAAAAAAAA", "caps/flooding"),
    ]
    for family, text, note in spam:
        # The starter policy blocks scam-like strings, rephrases generic spam.
        category = "SCAM_ADVERTISING" if any(x in text.lower() for x in ("free", "claim", "verify", "gift", "robux")) else "SPAM"
        action = "BLOCK" if category == "SCAM_ADVERTISING" else "REPHRASE"
        add(rows, seen, family, category, action, text, "none", category == "SCAM_ADVERTISING", False, note)


    # Large ordinary-chat and hard-negative block. The first model must not
    # collapse into "any rude message is hate".
    bad = ["trash", "awful", "terrible", "annoying", "broken", "boring",
           "useless", "bad", "cringe", "laggy", "a mess"]
    things = ["build", "aim", "farm", "team", "server", "update", "map",
              "fight", "redstone", "base", "PvP", "shop", "lag", "defense",
              "inventory", "house", "tower"]
    skills = ["PvP", "building", "farming", "redstone", "aim", "mining"]
    for i, value in enumerate(bad):
        for j, thing in enumerate(things):
            add(rows, seen, f"general.combo.{i}.{j}", "NONE", "ALLOW",
                f"this {thing} is {value} today", "none", False, True,
                "ordinary frustration")
            add(rows, seen, f"general.you.{i}.{j}", "NONE", "ALLOW",
                f"your {thing} is {value}", "none", False, True,
                "ordinary trash talk")
    for i, skill in enumerate(skills):
        for phrase in [
            f"you're trash at {skill}, uninstall",
            f"you're so bad at {skill}",
            f"your {skill} is terrible",
            f"get better at {skill}",
            f"you're cooked at {skill} next round",
        ]:
            add(rows, seen, f"general.skill.{i}.{abs(hash(phrase))}", "NONE", "ALLOW",
                phrase, "none", False, True, "ordinary gaming trash talk")

    game_threats = [
        "I'll destroy your base next round",
        "I'm going to steal your diamonds",
        "I'll beat you in the next match",
        "you're dead next round",
        "I'm going to win this time",
        "I'll find your base",
        "your team is going down",
        "I will take your loot",
        "I'm going to raid your farm",
        "see you in PvP, you're going down",
    ]
    for i, text in enumerate(game_threats):
        add(rows, seen, f"general.game-threat.{i}", "NONE", "ALLOW", text,
            "none", False, True, "game-only threat, not real-world violence")

    more_general = [
        "bro that was dumb",
        "nice one, genius",
        "you missed every shot",
        "this is the worst team ever",
        "you're cooked next round",
        "I'm going to win this time",
        "your defense is garbage",
        "lol you fell in lava",
        "what was that fight",
        "my team is throwing",
        "stop taking my loot",
        "why are you AFK",
        "that base is ugly",
        "your redstone is a mess",
        "I hate this map",
        "this update is trash",
        "my farm is broken again",
        "the lag is unreal",
        "I'm logging off, this is boring",
        "gg well played",
        "nice build, actually",
        "that was a good fight",
        "anyone want to trade diamonds",
        "where is the nearest village",
        "can someone help me find iron",
        "who has extra torches",
        "I fell in the void again",
        "my pickaxe broke",
        "the ender dragon is hard",
        "I need a break from this game",
        "shut up about the contest, it's just a game",
        "you are annoying but whatever",
        "that was a weird play",
        "why did you do that",
        "stop griefing my farm",
        "I'm bad at PvP, that's fine",
        "I lost my diamonds, pain",
        "the server is lagging again",
        "nice trap, I fell for it",
        "your shop prices are awful",
    ]
    for i, text in enumerate(more_general):
        add(rows, seen, f"general.more.{i}", "NONE", "ALLOW", text,
            "none", False, True, "ordinary chat or mild trash talk")

    # Additional identity-neutral hard negatives, varied by group.
    neutral_templates = [
        "{plural} are welcome here",
        "{plural} play on this server too",
        "is being {identity} okay here?",
        "my friend is {identity} and they are awesome",
        "I'm {identity} and I've been playing for years",
        "{plural} can be great teammates",
        "what does {identity} mean?",
        "I support {plural}",
        "{plural} exist and play Minecraft",
    ]
    for group, identity, plural, identity_phrase in GROUPS:
        for index, template in enumerate(neutral_templates):
            text = template.format(
                plural=plural,
                identity=identity_phrase.replace("being ", ""),
            )
            add(rows, seen, f"neutral.extra.{group}.{index}", "NONE", "ALLOW",
                text, group, False, True, "neutral identity discussion")

    # Generic suicide/self-harm instructions are targeted harassment, not a
    # protected-class slur. Keep variants labelled REPHRASE.
    for i, text in enumerate([
        "kill yourself",
        "go die",
        "kys",
        "neck yourself",
        "drink bleach",
        "nobody would miss you if you disappeared",
        "you should disappear",
    ]):
        add(rows, seen, f"harass.self-harm.{i}", "TARGETED_HARASSMENT",
            "REPHRASE", text, "other", True, False, "self-harm instruction")


    # Self-harm/abuse instructions and identity-based hate that do not always
    # contain a slur. These are important because a model that only recognizes
    # slur tokens is not enough.
    for i, text in enumerate([
        "neck yourself",
        "end yourself",
        "unalive yourself",
        "you should kill yourself",
        "I hope you die",
        "you're better off dead",
        "nobody would care if you died",
        "you should disappear forever",
        "go hang yourself",
        "take your own life",
    ]):
        add(rows, seen, f"harass.self-harm-more.{i}", "TARGETED_HARASSMENT",
            "REPHRASE", text, "other", True, False, "self-harm or death instruction")

    religion_hate = [
        "{plural} are ruining this server",
        "{plural} are a disease",
        "{plural} should not be allowed here",
        "{plural} are stupid and dangerous",
        "we need fewer {plural} here",
        "all {plural} should leave",
        "I hate {plural}",
        "{plural} don't belong in this community",
        "{plural} are a joke",
    ]
    for group, identity, plural, identity_phrase in GROUPS:
        if group != "religion":
            continue
        for i, template in enumerate(religion_hate):
            add(rows, seen, f"hate.religion-more.{i}.{group}", "TARGETED_HATE",
                "REPHRASE", template.format(plural=plural),
                group, True, False, "targeted religious hate")

    disability_hate = [
        "{plural} are useless",
        "{plural} should stay offline",
        "{plural} are broken",
        "mentally ill players should be banned",
        "autistic players are weird",
        "disabled players are a burden",
        "{plural} shouldn't be on this server",
        "I hate {plural}",
    ]
    for group, identity, plural, identity_phrase in GROUPS:
        if group != "disability":
            continue
        for i, template in enumerate(disability_hate):
            add(rows, seen, f"hate.disability-more.{i}.{group}", "TARGETED_HATE",
                "REPHRASE", template.format(plural=plural),
                group, True, False, "targeted disability hate")

    # Game-only threats and aggressive banter. These are NOT real-world threats
    # and must remain ALLOW for this policy.
    for i, text in enumerate([
        "I'll find your base",
        "I will find your base and raid it",
        "I'm going to destroy your base",
        "I'll steal all your diamonds",
        "I'll beat you in the next PvP match",
        "I will kill you in PvP next round",
        "I'm going to hunt you down in the game",
        "your base is going down",
        "I'll take your loot after this",
        "I'm coming for your farm",
        "I will end your win streak",
        "you're dead in the next round",
        "I'll camp your spawn",
        "I'm going to trap your base",
        "I'll blow up your build in the game",
    ]):
        add(rows, seen, f"general.game-threat-more.{i}", "NONE", "ALLOW", text,
            "none", False, True, "game-only threat or banter")

    # A few additional ordinary insults and identity-neutral remarks.
    for i, text in enumerate([
        "you're an idiot, but this is just a game",
        "you suck, no hard feelings",
        "that was a stupid play",
        "you're terrible at this, honestly",
        "your team is a mess",
        "I can't believe you missed that",
        "why are you like this",
        "this is why we can't have nice things",
        "the lag is making everyone play bad",
        "I'm muting chat for a bit",
        "my teammate is a bot",
        "I'm going to build a giant farm",
        "anyone have spare iron",
        "where did my dog go",
        "that creeper ruined everything",
        "I love this server sometimes",
        "the new update is actually fun",
        "nice base, how long did it take",
        "can I join your team",
        "good luck next round",
    ]):
        add(rows, seen, f"general.extra.{i}", "NONE", "ALLOW", text,
            "none", False, True, "ordinary chat")


    # --- Iteration 2: paraphrase families for patterns the first model missed. ---
    # Deliberately NOT the exact challenge sentences. Each family varies wording so
    # the challenge set still measures generalization rather than memorization.

    # Identity trait framed as a defect ("your sexuality is a problem").
    trait_defect = [
        "{possessive} {trait} is a problem",
        "{possessive} {trait} is disgusting",
        "{possessive} {trait} is a plague on this server",
        "{possessive} {trait} makes {pronoun} worthless",
        "{possessive} {trait} is why nobody likes {pronoun}",
        "there is something wrong with {possessive} {trait}",
    ]
    trait_rows = [
        ("sexual_orientation", "sexuality", "your", "you"),
        ("sexual_orientation", "sexual orientation", "your", "you"),
        ("gender_identity", "gender identity", "your", "you"),
        ("gender_identity", "gender", "your", "you"),
        ("religion", "religion", "your", "you"),
        ("religion", "faith", "your", "you"),
        ("race_ethnicity", "race", "your", "you"),
        ("race_ethnicity", "ethnicity", "your", "you"),
        ("nationality", "nationality", "your", "you"),
        ("nationality", "accent", "your", "you"),
        ("disability", "disability", "your", "you"),
        ("disability", "autism", "your", "you"),
    ]
    for group, trait, possessive, pronoun in trait_rows:
        for index, template in enumerate(trait_defect):
            add(rows, seen, f"hate.trait-defect.{trait}.{index}", "TARGETED_HATE",
                "REPHRASE", template.format(possessive=possessive, trait=trait, pronoun=pronoun),
                group, True, False, "identity trait framed as a defect")

    # Group-level incompetence/generalization ("women are bad at PvP").
    gender_groups = [
        ("gender_identity", "women"),
        ("gender_identity", "girls"),
        ("gender_identity", "females"),
        ("gender_identity", "women players"),
        ("gender_identity", "girl gamers"),
    ]
    game_incompetence = [
        "{plural} are bad at PvP",
        "{plural} are terrible at this game",
        "{plural} can't build to save their lives",
        "{plural} are worse at every game",
        "{plural} shouldn't play competitive modes",
        "{plural} only win because someone carries them",
        "{plural} are a liability in a team",
        "{plural} don't have the reflexes for this",
    ]
    for group, plural in gender_groups:
        for index, template in enumerate(game_incompetence):
            add(rows, seen, f"hate.gender-incompetence.{plural}.{index}".replace(" ", "_"),
                "TARGETED_HATE", "REPHRASE", template.format(plural=plural),
                group, True, False, "gender-based generalization")

    # Antisemitic and general identity-conspiracy framing.
    conspiracy_groups = [
        ("religion", "Jewish players", "Jewish people", "Jews"),
        ("religion", "Muslim players", "Muslim people", "Muslims"),
        ("race_ethnicity", "Black players", "Black people", "Black communities"),
        ("nationality", "immigrants", "immigrant communities", "foreigners"),
        ("sexual_orientation", "gay players", "gay people", "gay communities"),
        ("gender_identity", "trans players", "trans people", "trans communities"),
    ]
    conspiracies = [
        "{group} control the economy on this server",
        "{group} run this community behind the scenes",
        "{group} are secretly ruining the game",
        "{group} are organizing against normal players",
        "{group} have too much influence here",
        "{group} are taking over the server",
        "{group} are the reason the community is dying",
        "{group} are plotting to push everyone else out",
    ]
    for group, plural, people, short in conspiracy_groups:
        for index, template in enumerate(conspiracies):
            add(rows, seen, f"hate.conspiracy.{short}.{index}".replace(" ", "_"),
                "TARGETED_HATE", "REPHRASE",
                template.format(group=people), group, True, False,
                "identity conspiracy hate")

    # Exclusion of a person by identity ("nobody wants your kind on this team").
    exclusion_templates = [
        "nobody wants your kind on this team",
        "nobody wants your kind in this clan",
        "we don't want your kind here",
        "your kind should not be allowed to play",
        "your kind ruins every team",
        "people like you should be removed from this server",
        "this server would be better without your kind",
        "your kind is not welcome in our community",
        "go find a server for your kind",
        "keep your kind out of competitive modes",
    ]
    for index, text in enumerate(exclusion_templates):
        for group, identity, plural, identity_phrase in GROUPS:
            add(rows, seen, f"hate.kind-exclusion.{index}.{group}", "TARGETED_HATE",
                "REPHRASE", text, group, True, False, "identity-based exclusion")

    # Targeted degradation that is not a slur ("you're a burden to every team").
    burden_templates = [
        "you're a burden to every team",
        "you are a liability to everyone here",
        "every team is worse with you on it",
        "people are better off without you",
        "this server would improve if you left",
        "please disappear from this server",
        "just log off and don't come back",
        "nobody would miss you here",
        "you add nothing to this community",
        "the game is better when you're not online",
        "everyone would celebrate if you quit",
        "you make every lobby worse",
    ]
    for index, text in enumerate(burden_templates):
        add(rows, seen, f"harass.burden.{index}", "TARGETED_HARASSMENT",
            "REPHRASE", text, "other", True, False, "targeted degradation")

    # Ordinary hatred of a game activity must remain ALLOW. This is the exact
    # "I hate mining, it takes forever" failure mode, generalized.
    activities = ["mining", "farming", "grinding", "this map", "this update",
                  "this dungeon", "this boss", "the nether", "the end", "redstone",
                  "building roofs", "fishing", "trading", "the tutorial", "this quest"]
    vent_templates = [
        "I hate {activity}, it takes forever",
        "I hate {activity}, it's so boring",
        "I hate {activity} in this game",
        "I really hate {activity}",
        "{activity} is so tedious",
        "I'm sick of {activity}",
        "I can't stand {activity} anymore",
        "{activity} takes way too long",
    ]
    for activity in activities:
        for index, template in enumerate(vent_templates):
            add(rows, seen, f"general.activity-hate.{activity}.{index}".replace(" ", "_"),
                "NONE", "ALLOW", template.format(activity=activity),
                "none", False, True, "ordinary venting about game content")

    # Ordinary dislike of a person's behaviour, not identity.
    behaviour_hate = [
        "I hate players who steal loot",
        "I hate when teammates don't communicate",
        "I hate campers in this game",
        "I hate people who grief spawn",
        "I hate spam in chat",
        "I hate laggy players who blame the server",
        "I hate when my team throws",
        "I hate tryhards in casual lobbies",
    ]
    for index, text in enumerate(behaviour_hate):
        add(rows, seen, f"general.behaviour-hate.{index}", "NONE", "ALLOW", text,
            "none", False, True, "dislike of behaviour, not identity")


    # Wishing a player would disappear/never return, including login framing.
    absence_wish = [
        "I hope you never log in again",
        "I hope you never come back",
        "I hope you quit this server for good",
        "I hope you never play here again",
        "I hope you disappear from this community",
        "I hope you leave and never return",
        "I hope you get banned forever",
        "I hope you never join another game",
        "I hope you stop playing completely",
        "I hope you are gone for good",
    ]
    for index, text in enumerate(absence_wish):
        add(rows, seen, f"harass.absence-wish.{index}", "TARGETED_HARASSMENT",
            "REPHRASE", text, "other", True, False, "targeted exclusion wish")

    # More ordinary game-content venting, expanded vocabulary.
    more_activities = ["chopping trees", "strip mining", "villager trading",
                       "enchanting", "brewing potions", "the grind", "sailing",
                       "base defence", "spawn clearing", "inventory sorting"]
    more_vent = [
        "I hate {activity}, it's a slog",
        "{activity} is the worst part of the game",
        "I'm tired of {activity}",
        "why does {activity} take so long",
        "I don't want to do {activity} today",
        "{activity} is so repetitive",
    ]
    for activity in more_activities:
        for index, template in enumerate(more_vent):
            add(rows, seen, f"general.activity-hate2.{activity}.{index}".replace(" ", "_"),
                "NONE", "ALLOW", template.format(activity=activity),
                "none", False, True, "ordinary venting about game content")


    # --- Iteration 3: homophobic framing, religion-as-disease, exclusion wording. ---
    for index, text in enumerate([
        "your lifestyle is against nature",
        "your lifestyle is unnatural",
        "being gay goes against nature",
        "homosexuality is a mental illness",
        "being gay is a sickness",
        "being trans is a mental disorder",
        "your sexuality is a sin",
        "your lifestyle is a disease",
        "people like you are broken inside",
        "your identity is a mental illness",
        "being queer is unnatural",
        "your sexuality is disgusting to normal people",
    ]):
        add(rows, seen, f"hate.lifestyle.{index}", "TARGETED_HATE", "REPHRASE", text,
            "sexual_orientation", True, False, "homophobic framing")

    for group, identity, plural, identity_phrase in GROUPS:
        if group != "religion":
            continue
        for index, template in enumerate([
            "{possessive} religion is a cancer on this server",
            "{possessive} religion is a poison",
            "{possessive} religion is a plague",
            "{possessive} faith is a disease",
            "your beliefs are ruining this community",
            "your religion makes you dangerous",
        ]):
            add(rows, seen, f"hate.region-disease.{group}.{index}", "TARGETED_HATE",
                "REPHRASE", template.format(possessive="your"), group, True, False,
                "religion framed as disease")

    for index, text in enumerate([
        "nobody will miss you when you leave",
        "nobody is going to miss you",
        "no one would notice if you were gone",
        "everyone would be happier without you",
        "people would celebrate if you quit",
        "the server would be better if you left",
        "just uninstall and never come back",
        "do everyone a favour and quit",
        "do us all a favour and leave",
        "please go away and stay away",
        "you contribute nothing to this team",
        "you add nothing to this team",
        "you are dead weight on every team",
        "you're holding every team back",
        "we would win more without you",
        "you are the weakest player here",
    ]):
        add(rows, seen, f"harass.exclusion-more.{index}", "TARGETED_HARASSMENT",
            "REPHRASE", text, "other", True, False, "targeted exclusion/degradation")

    # Hard negative: countries and broad inclusivity, not "your kind".
    for index, text in enumerate([
        "people from every country play here",
        "players from all over the world join this server",
        "we have players from many countries",
        "anyone from any country can join",
        "this server has an international community",
        "I've met players from dozens of countries here",
    ]):
        add(rows, seen, f"general.international.{index}", "NONE", "ALLOW", text,
            "nationality", False, True, "neutral international inclusivity")


    # --- Iteration 4: slur-in-context diversity and remaining paraphrase gaps. ---
    # A slur inside a question/statement must not be read as neutral just because
    # the surrounding words look like ordinary chat.
    slur_context = [
        "why is this {slur} still in the lobby",
        "why is that {slur} still here",
        "who let this {slur} join",
        "who invited this {slur}",
        "look at that {slur} trying to play",
        "that {slur} is back again",
        "this {slur} keeps talking",
        "somebody kick this {slur}",
        "get this {slur} out of here",
        "nobody wants this {slur} in the game",
        "this {slur} is ruining the match",
        "why does this {slur} keep typing",
        "another {slur} joined the server",
        "the {slur} is crying again",
        "that {slur} can't play at all",
        "{slur}s like you ruin every lobby",
        "you and every other {slur} should leave",
        "I can't stand {slur}s in this game",
        "there goes another {slur}",
        "this lobby is full of {slur}s",
    ]
    for group, identity, plural, identity_phrase in GROUPS:
        for index, template in enumerate(slur_context):
            add(rows, seen, f"hate.slur-context.{index}.{group}", "TARGETED_HATE",
                "REPHRASE", template.format(slur="{SLUR}"), group, True, False,
                "slur used in a targeted sentence")

    # Gender + skill insults.
    for index, text in enumerate([
        "girls can't aim in shooters",
        "girls can't aim",
        "women can't aim",
        "female players can't aim",
        "girls are bad at aiming",
        "women are bad at aiming",
        "girls have no game sense",
        "women have no game sense",
        "girls shouldn't play shooters",
        "women shouldn't play PvP games",
        "girls only play support",
        "women can't handle competitive games",
    ]):
        add(rows, seen, f"hate.gender-aim.{index}", "TARGETED_HATE", "REPHRASE",
            text, "gender_identity", True, False, "gender skill generalization")

    # Nationality/accent insults without the word "accent" only.
    for index, text in enumerate([
        "your accent is horrible to listen to",
        "your accent is awful",
        "your accent is disgusting",
        "I can't stand your accent",
        "your voice sounds stupid because of your accent",
        "people with your accent sound uneducated",
        "your accent makes you sound dumb",
        "go learn the language before talking",
    ]):
        add(rows, seen, f"hate.accent.{index}", "TARGETED_HATE", "REPHRASE",
            text, "nationality", True, False, "nationality/accent insult")

    # Religion framed as poison/cancer with varied nouns.
    for group, identity, plural, identity_phrase in GROUPS:
        if group != "religion":
            continue
        for index, template in enumerate([
            "your faith is a poison in this server",
            "your faith is a poison",
            "your faith is a cancer",
            "your faith is ruining the community",
            "your beliefs are a disease",
            "your religion poisons this community",
        ]):
            add(rows, seen, f"hate.faith-poison.{group}.{index}", "TARGETED_HATE",
                "REPHRASE", template, group, True, False, "religion framed as poison")

    # Additional exclusion/degradation wording.
    for index, text in enumerate([
        "go find another game and stay there",
        "go find another server and stay there",
        "you're dead weight to this team",
        "you're dead weight to every team",
        "you are dead weight here",
        "everyone is sick of your voice",
        "everyone is tired of hearing you",
        "nobody can stand your voice",
        "your voice is unbearable",
        "please stop talking forever",
        "we would all prefer silence from you",
        "you are the worst thing about this community",
        "this community is worse because of you",
        "you make this server worse for everyone",
    ]):
        add(rows, seen, f"harass.degrade-more.{index}", "TARGETED_HARASSMENT",
            "REPHRASE", text, "other", True, False, "targeted degradation/exclusion")

    # Hard negatives around game theft and broad inclusivity.
    for index, text in enumerate([
        "I'm going to steal your enchanted gear",
        "I'll steal your enchanted sword next round",
        "I'm taking your enchanted armour in the next game",
        "I'll loot your chests after the raid",
        "I'm going to steal your best gear",
        "players from many backgrounds are here",
        "players from many backgrounds play here",
        "people from many backgrounds enjoy this server",
        "our community has people from many backgrounds",
        "this server welcomes people from many backgrounds",
    ]):
        add(rows, seen, f"general.hard-more.{index}", "NONE", "ALLOW", text,
            "none", False, True, "game theft or neutral inclusivity")


    # --- Iteration 5: remaining threat/incitement/spam phrasings. ---
    for index, text in enumerate([
        "I'm going to smash your face",
        "I'll smash your face in",
        "I will smash your head",
        "I'm going to punch your face",
        "I'll bust your nose",
        "I will knock your teeth out",
        "I'm going to stomp you",
        "I'll choke you out",
        "I will break your fingers",
        "I'll crush your throat",
    ]):
        add(rows, seen, f"threat.smash.{index}", "THREAT", "BLOCK", text,
            "none", True, False, "physical threat")

    for index, text in enumerate([
        "someone ought to stab that kid",
        "someone should stab that kid",
        "somebody should stab him",
        "someone ought to shoot that kid",
        "somebody should punch that kid",
        "someone needs to beat him up",
        "somebody ought to hurt him",
        "someone should attack that player",
    ]):
        add(rows, seen, f"threat.incite-more.{index}", "THREAT", "BLOCK", text,
            "none", True, False, "incitement to violence")

    for index, text in enumerate([
        "SALE SALE SALE SALE SALE",
        "SALE SALE SALE SALE",
        "FREE FREE FREE FREE FREE",
        "JOIN JOIN JOIN JOIN",
        "BUY BUY BUY BUY",
        "CLICK CLICK CLICK CLICK",
        "HURRY HURRY HURRY HURRY",
    ]):
        add(rows, seen, f"spam.flood-more.{index}", "SPAM", "REPHRASE", text,
            "none", False, False, "flooding/caps spam")


    # --- Iteration 6: neutral identity questions and mild "slow" criticism. ---
    for group, identity, plural, identity_phrase in GROUPS:
        for index, template in enumerate([
            "is this community open to {plural}?",
            "is this server welcoming to {plural}?",
            "are {plural} welcome here?",
            "does this server allow {plural}?",
            "can {plural} join this community?",
            "is it okay to be {identity} here?",
        ]):
            add(rows, seen, f"neutral.question.{group}.{index}", "NONE", "ALLOW",
                template.format(plural=plural,
                                identity=identity_phrase.replace("being ", "")),
                group, False, True, "neutral identity question")

    for index, text in enumerate([
        "we have players from all continents",
        "players from every continent play here",
        "our players come from all over the world",
        "we have people from many backgrounds",
        "our community includes people from everywhere",
        "players of all backgrounds are welcome",
    ]):
        add(rows, seen, f"neutral.continents.{index}", "NONE", "ALLOW", text,
            "other", False, True, "neutral inclusivity")

    for index, text in enumerate([
        "you're kinda slow at crafting",
        "you're a bit slow at building",
        "you're slow at gathering",
        "you're kind of slow at this",
        "you're a little slow today",
        "you're slow but you're learning",
        "you're not the fastest builder",
    ]):
        add(rows, seen, f"general.slow.{index}", "NONE", "ALLOW", text,
            "none", False, True, "mild ordinary criticism")


    # "X explains why you're bad" framing.
    for group, trait in [("race_ethnicity", "race"), ("gender_identity", "gender"),
                        ("nationality", "nationality"), ("religion", "religion"),
                        ("sexual_orientation", "sexuality"), ("disability", "disability")]:
        for index, template in enumerate([
            "your {trait} explains why you're bad at this",
            "your {trait} explains why you lose every game",
            "you're bad at this because of your {trait}",
            "you only lose because of your {trait}",
            "your {trait} is why you can't compete",
            "people with your {trait} can't play this",
        ]):
            add(rows, seen, f"hate.trait-explains.{group}.{index}", "TARGETED_HATE",
                "REPHRASE", template.format(trait=trait), group, True, False,
                "identity used to explain incompetence")


    # --- Iteration 7: community-threat framing, uninstall/offline exclusion. ---
    for group, identity, plural, identity_phrase in GROUPS:
        for index, template in enumerate([
            "{plural} are a threat to this community",
            "{plural} are a danger to this server",
            "{plural} are dangerous to normal players",
            "{plural} are a risk to everyone here",
            "{plural} are not safe to have around",
        ]):
            add(rows, seen, f"hate.community-threat.{group}.{index}", "TARGETED_HATE",
                "REPHRASE", template.format(plural=plural), group, True, False,
                "identity framed as a threat")

    for index, text in enumerate([
        "uninstall the game and leave",
        "uninstall and get out",
        "delete the game and don't come back",
        "you should uninstall",
        "just uninstall already",
        "the server is better when you're offline",
        "this server is better without you online",
        "everyone prefers it when you're offline",
        "things are calmer when you're not online",
        "you're a waste of a teammate",
        "you're a waste of a team slot",
        "you're a waste of space on this team",
        "you are a useless teammate",
    ]):
        add(rows, seen, f"harass.uninstall-offline.{index}", "TARGETED_HARASSMENT",
            "REPHRASE", text, "other", True, False, "targeted exclusion/degradation")

    # Hard negatives: ordinary sloppiness and lag complaints.
    for index, text in enumerate([
        "you're a bit sloppy with your blocks",
        "your blocks are a little sloppy",
        "your building is sloppy in places",
        "that was a sloppy jump",
        "I keep lagging out at the worst time",
        "my connection drops at the worst moment",
        "the lag hits at the worst time",
        "I lag out whenever a fight starts",
        "my ping spikes at the worst time",
    ]):
        add(rows, seen, f"general.sloppy-lag.{index}", "NONE", "ALLOW", text,
            "none", False, True, "ordinary criticism or lag complaint")


    # --- Iteration 8: invasion framing and "your kind" place variants. ---
    for group, identity, plural, identity_phrase in GROUPS:
        for index, template in enumerate([
            "{plural} are invading this community",
            "{plural} are invading this server",
            "{plural} are taking over this game",
            "{plural} are infiltrating our community",
            "{plural} are flooding into this server",
            "{plural} are colonizing this community",
        ]):
            add(rows, seen, f"hate.invasion.{group}.{index}", "TARGETED_HATE",
                "REPHRASE", template.format(plural=plural), group, True, False,
                "identity framed as invasion")

    for index, text in enumerate([
        "your kind is not welcome in this clan",
        "your kind is not welcome in this guild",
        "your kind is not welcome in this team",
        "your kind is not welcome on this server",
        "your kind is not welcome in this community",
        "your kind isn't welcome here",
        "we don't welcome your kind",
        "your kind has no place here",
        "there is no place for your kind here",
        "your kind should stay out of this clan",
    ]):
        add(rows, seen, f"hate.kind-place.{index}", "TARGETED_HATE", "REPHRASE",
            text, "other", True, False, "identity-based exclusion")

    # --- Iteration 9: broader corpus (more syntax, more hard negatives). ---

    # Slurs embedded in short, varied chat turns. The earlier blocks cover
    # statements; this block adds imperative, mocking and reaction phrasing.
    slur_context_more = [
        "you absolute {slur}",
        "shut your mouth, {slur}",
        "nobody asked, {slur}",
        "cry more, {slur}",
        "get lost, {slur}",
        "you're embarrassing, {slur}",
        "what a joke, {slur}",
        "typical {slur} behaviour",
        "another {slur} crying in chat",
        "this {slur} again",
        "mute this {slur}",
        "ban this {slur}",
        "report this {slur}",
        "of course the {slur} loses",
        "go back to your server, {slur}",
        "you're not wanted here, {slur}",
        "keep crying, {slur}",
        "nice try, {slur}",
        "look who's talking, {slur}",
        "imagine being this much of a {slur}",
    ]
    for group, identity, plural, identity_phrase in GROUPS:
        for index, template in enumerate(slur_context_more):
            add(rows, seen, f"hate.slur-turn.{index}.{group}", "TARGETED_HATE",
                "REPHRASE", template.format(slur="{SLUR}"), group, True, False,
                "slur in a short targeted chat turn")

    # Identity traits attacked through ability, trust or disgust framing.
    trait_attack = [
        "people with your {trait} shouldn't play",
        "anyone with your {trait} is a liability",
        "your {trait} makes you unbearable",
        "I can't take you seriously because of your {trait}",
        "your {trait} is the reason you're alone",
        "you only got here because of your {trait}",
        "your {trait} should disqualify you",
        "nobody respects people with your {trait}",
        "your {trait} is a red flag",
        "your {trait} makes me sick",
        "I don't want people with your {trait} on my team",
        "people like you are a stain on this community",
    ]
    trait_names = [
        ("sexual_orientation", "sexuality"),
        ("sexual_orientation", "sexual orientation"),
        ("gender_identity", "gender"),
        ("gender_identity", "gender identity"),
        ("religion", "religion"),
        ("religion", "faith"),
        ("race_ethnicity", "race"),
        ("race_ethnicity", "ethnicity"),
        ("nationality", "nationality"),
        ("nationality", "accent"),
        ("disability", "disability"),
        ("disability", "autism"),
    ]
    for group, trait in trait_names:
        for index, template in enumerate(trait_attack):
            add(rows, seen, f"hate.trait-attack.{trait}.{index}".replace(" ", "_"),
                "TARGETED_HATE", "REPHRASE", template.format(trait=trait),
                group, True, False, "identity trait attacked as ability or character")

    # Group-level hate and exclusion with different verbs.
    group_more = [
        "{plural} are useless at this game",
        "{plural} are a plague on this server",
        "{plural} should be removed from the community",
        "{plural} are not real players",
        "{plural} are all the same",
        "{plural} bring nothing to this server",
        "{plural} are the worst part of this community",
        "{plural} can't be trusted",
        "{plural} are always trouble",
        "{plural} are ruining the game for everyone",
        "there are too many {plural} here",
        "this server has enough {plural} already",
        "{plural} should find another server",
        "I'm tired of {plural} in this community",
        "{plural} don't deserve to play here",
    ]
    for group, identity, plural, identity_phrase in GROUPS:
        for index, template in enumerate(group_more):
            add(rows, seen, f"hate.group-more.{index}.{group}", "TARGETED_HATE",
                "REPHRASE", template.format(plural=plural), group, True, False,
                "group-directed hate or exclusion")

    # Targeted degradation and exclusion without a protected-class target.
    harassment_more = [
        "nobody here wants you around",
        "you're not wanted on this team",
        "everyone would be relieved if you left",
        "leave and never show your face again",
        "you're the most annoying person here",
        "you bring out the worst in everyone",
        "nobody trusts you",
        "you're a joke to everyone here",
        "people laugh at you behind your back",
        "you're pathetic",
        "you're a clown",
        "you drag everyone down with you",
        "this team would work better without you",
        "you're the problem in every lobby",
        "quit wasting everyone's time",
        "do us a favour and disappear",
        "you are not welcome in this community",
        "nobody has fun when you're here",
        "your presence ruins the game",
        "we'd all be happier if you logged off",
        "you're a waste of a player",
        "you should be embarrassed to play here",
        "nobody wants to hear from you",
        "stop showing up to these games",
        "you always make everything worse",
        "everyone is tired of your excuses",
        "you're hopeless",
        "you're beyond help",
        "you're a lost cause",
        "you should give up",
        "give up, you're never going to improve",
        "you're not cut out for this",
        "walk away and don't look back",
        "we don't need you here",
        "there's no place for you on this team",
        "you've overstayed your welcome",
        "you make me want to quit",
        "you're the reason people stop playing",
        "nobody would choose you for a team",
        "you're the last pick for a reason",
    ]
    for index, text in enumerate(harassment_more):
        add(rows, seen, f"harass.more-more.{index}", "TARGETED_HARASSMENT",
            "REPHRASE", text, "other", True, False, "targeted degradation or exclusion")

    # Real-world threats and incitement, kept non-graphic.
    threat_more = [
        "I'll break your spine",
        "I'll knock you out",
        "I'll choke you",
        "I'll stomp on your head",
        "I'll cut you",
        "I'll beat you until you can't walk",
        "I'll put you in hospital",
        "I'll hunt you down in real life",
        "I'll find your workplace",
        "I know your address",
        "I know your full name",
        "I'll leak your phone number",
        "I'll swat your house",
        "I'll send people to your house",
        "you're dead meat",
        "you won't survive the week",
        "watch your back after school",
        "I'll get my friends to jump you",
        "I'll wait outside your house",
        "I'll go after your parents",
        "I will make your life hell in person",
        "I'm going to follow you home",
        "I'll show up at your school",
        "I'll hurt you where nobody can see",
        "I'll make sure you regret this in person",
    ]
    for index, text in enumerate(threat_more):
        add(rows, seen, f"threat.more.{index}", "THREAT", "BLOCK", text,
            "none", True, False, "real-world threat")
        third = text.replace(" your ", " ExamplePlayer's ", 1).replace(" you ", " ExamplePlayer ", 1)
        if third != text:
            add(rows, seen, f"threat.more.third.{index}", "THREAT", "BLOCK",
                third, "none", True, False, "real-world threat, third person")

    # More ordinary venting about game content. This is the main false-positive
    # risk for a hate/harassment model.
    activities_more = [
        "mining", "farming", "grinding", "building", "redstone", "fishing",
        "trading", "enchanting", "brewing", "smelting", "crafting", "exploring",
        "the nether", "the end", "this dungeon", "this boss", "this map",
        "this update", "this quest", "the tutorial", "inventory sorting",
        "base defence", "spawn clearing", "chopping trees", "strip mining",
    ]
    activity_vent = [
        "I hate {activity}, it's so tedious",
        "{activity} is the worst part of this game",
        "I'm so tired of {activity}",
        "why does {activity} take so long",
        "{activity} is such a grind",
        "I can't stand {activity} anymore",
    ]
    for activity in activities_more:
        for index, template in enumerate(activity_vent):
            add(rows, seen, f"general.activity-more.{activity}.{index}".replace(" ", "_"),
                "NONE", "ALLOW", template.format(activity=activity),
                "none", False, True, "ordinary venting about game content")

    # Ordinary criticism and trash talk with no protected-class target.
    ordinary_more = [
        "your aim is tragic, uninstall",
        "you play like you're new here",
        "that was a bronze-level play",
        "you're not very good at this",
        "your building needs work",
        "nice try, maybe next time",
        "you missed every shot",
        "that was a terrible decision",
        "your defence is full of holes",
        "you're making this too easy",
        "you're slow at everything",
        "you keep making the same mistake",
        "that was embarrassing to watch",
        "you're throwing the game",
        "your team is carrying you",
        "you got lucky last round",
        "you can't aim today",
        "your movement is predictable",
        "you're easy to read",
        "you keep walking into traps",
        "your strategy makes no sense",
        "you're wasting everyone's time",
        "that was a waste of resources",
        "you're not ready for ranked",
        "you should practice more",
        "you're a beginner at best",
        "your farm is inefficient",
        "your redstone is a mess",
        "your base layout is confusing",
        "that build is ugly",
        "your shop prices are terrible",
        "you missed the easy jump",
        "you're not paying attention",
        "you keep ignoring the objective",
        "your team is a disaster",
        "you're the weak link",
        "that was a pathetic attempt",
        "you're playing like a bot",
        "you're so predictable",
        "you need to get better",
        "you're not fooling anyone",
        "you're all talk",
        "that was a dumb play",
        "you're better than this, surely",
        "you're making us look bad",
        "you're not helping the team",
        "you keep dying first",
        "you're wasting your potential",
        "you're too slow to react",
        "you're not good under pressure",
        "you're a one-trick player",
        "you can't handle a real fight",
        "you're all aim and no brain",
        "you're just lucky",
        "you're not as good as you think",
        "that was a beginner mistake",
        "you're making the game boring",
        "you're taking this too seriously",
        "you're playing on easy mode",
        "you're not even trying",
        "you're the reason we lost",
        "you're holding us back",
        "you should sit this one out",
        "you're not ready for this",
        "you're out of your depth",
        "you keep letting the team down",
        "you're too predictable to win",
        "you're not a threat",
        "you're harmless",
        "you're not intimidating anyone",
        "your reputation is overrated",
        "you're all hype",
        "you're not the player you were",
        "you're washed up",
        "you're past your prime",
        "you're a has-been",
        "you're not special",
        "you're nothing without your team",
    ]
    for index, text in enumerate(ordinary_more):
        add(rows, seen, f"general.criticism.{index}", "NONE", "ALLOW", text,
            "none", False, True, "ordinary criticism or trash talk")

    # Game-only threats and raid banter must remain ALLOW.
    game_threat_more = [
        "I'll blow up your base next round",
        "I'm going to raid your chests",
        "I'll take your gear after this match",
        "I'm coming for your spawn",
        "I'll camp your base all game",
        "I'm going to trap your portal",
        "I'll destroy your farm in the next war",
        "I'm going to hunt you in the next round",
        "I'll steal your enchanted armour",
        "I'm going to wipe your team",
        "I'll beat you in the rematch",
        "I'm going to outbuild you",
        "I'll take your diamonds next game",
        "I'm going to break your bed",
        "I'll rush your base",
        "I'm going to flank your team",
        "I'll burn your house down in game",
        "I'm going to kill your character",
        "I'll knock you into the void",
        "I'm going to push your lane",
        "I'll steal your kills",
        "I'm going to spawn-camp you",
        "I'll destroy your tower",
        "I'm going to take your land",
        "I'll beat your team by myself",
        "I'm going to make you lose",
        "I'll outplay you next time",
        "I'm going to win this duel",
        "I'll take the objective from you",
        "I'm going to ruin your win streak",
        "I'll make you rage quit in game",
        "I'm going to trap you in the nether",
        "I'll blow your cover",
        "I'm going to steal your loot",
        "I'll take your spot on the leaderboard",
        "I'm going to beat your record",
        "I'll finish you in the arena",
        "I'm going to win the tournament",
        "I'll knock your team out",
        "I'm going to take first place",
    ]
    for index, text in enumerate(game_threat_more):
        add(rows, seen, f"general.game-threat-more.{index}", "NONE", "ALLOW",
            text, "none", False, True, "game-only threat or banter")

    # Neutral chat and help requests.
    ordinary_chat = [
        "anyone have spare arrows",
        "where is the closest village",
        "does anyone want to team up",
        "I need help with this puzzle",
        "how do I make a beacon",
        "what's the best food in this game",
        "can someone show me the redstone trick",
        "I'm new here, any tips",
        "how long until the next event",
        "who is hosting the tournament",
        "nice base, how did you build it",
        "I finally beat the boss",
        "that was a close match",
        "good game everyone",
        "thanks for the help",
        "I found diamonds",
        "where did my horse go",
        "my farm is finally working",
        "the new update looks fun",
        "anyone want to trade",
        "I'm building a castle",
        "can I join your clan",
        "what time is the drop party",
        "is the server down for anyone else",
        "my game keeps crashing",
        "I need more storage chests",
        "how do I tame a wolf",
        "where can I find obsidian",
        "I'm looking for a builder",
        "anyone good at terraforming",
        "that build took me hours",
        "I love this server",
        "the community here is nice",
        "thanks for the trade",
        "see you next round",
        "good luck in the finals",
        "that was a fun raid",
        "I'm taking a short break",
        "back in a minute",
        "anyone else lagging",
        "my ping is finally stable",
        "I found a secret room",
        "this map has cool secrets",
        "the music in this area is great",
        "I'm collecting all the achievements",
        "does anyone know the seed",
        "can we build a community farm",
        "I'll share my resources",
        "who needs food",
        "I have extra potions",
        "let's build a railway",
        "that was a clean dodge",
        "nice clutch at the end",
        "we make a good team",
        "I'm proud of that build",
        "the sunset in this game is beautiful",
        "I finally got the achievement",
        "this boss is hard but fair",
        "I'm learning redstone slowly",
        "anyone want to explore the end",
        "I'll guard the base tonight",
        "we should plan the next raid",
        "good call on the strategy",
        "that trap was clever",
        "I fell for it completely",
        "you got me there",
        "that was a fair fight",
        "I'll get you next time, in game",
        "rematch later",
        "I'm improving at PvP",
        "my building is getting better",
        "I like your skin",
        "where did you get that cape",
        "that pet is adorable",
        "I named my dog after you",
        "this server has good vibes",
        "thanks for being welcoming",
        "I appreciate the help",
        "that was kind of you",
        "I'm glad I joined",
        "the staff here are helpful",
        "this event is fun",
        "I can't wait for the next update",
    ]
    for index, text in enumerate(ordinary_chat):
        add(rows, seen, f"general.chat-more.{index}", "NONE", "ALLOW", text,
            "none", False, True, "ordinary chat or help request")

    # Neutral identity questions for every supported group.
    neutral_question_more = [
        "is this server friendly to {plural}?",
        "do {plural} play here?",
        "are there other {plural} on this server?",
        "can {plural} join this clan?",
        "is it okay to be {identity} here?",
        "does this community welcome {plural}?",
        "are {plural} treated fairly here?",
        "I'm {identity}, is that a problem?",
        "my friend is {identity} and wants to join",
        "we should make {plural} feel welcome",
    ]
    for group, identity, plural, identity_phrase in GROUPS:
        for index, template in enumerate(neutral_question_more):
            add(rows, seen, f"neutral.more-question.{group}.{index}", "NONE",
                "ALLOW", template.format(
                    plural=plural,
                    identity=identity_phrase.replace("being ", ""),
                ), group, False, True, "neutral identity question or support")

    # --- Iteration 10: inclusivity and roster wording gaps. ---
    for index, text in enumerate([
        "our players come from every continent",
        "our players come from all over",
        "our community spans every continent",
        "we have players on every continent",
        "this server has players from every continent",
        "our player base is spread across the world",
        "players from every part of the world play here",
        "we welcome players from every region",
    ]):
        add(rows, seen, f"neutral.every-continent.{index}", "NONE", "ALLOW",
            text, "other", False, True, "neutral international inclusivity")

    for index, text in enumerate([
        "you're a waste of a roster spot",
        "you're a waste of a roster slot",
        "you waste a roster spot",
        "we waste a roster spot on you",
        "you take up a roster spot for nothing",
        "you're not worth a roster spot",
        "you shouldn't be on the roster",
        "take yourself off the roster",
    ]):
        add(rows, seen, f"harass.roster.{index}", "TARGETED_HARASSMENT",
            "REPHRASE", text, "other", True, False, "targeted degradation")

    # --- Iteration 11: gender blame and location-threat wording. ---
    for index, text in enumerate([
        "women have ruined ranked play",
        "women ruined competitive play",
        "girls have ruined this game",
        "women have destroyed this community",
        "females have ruined the game for everyone",
        "women are why ranked is a joke",
        "girls are why this game is dying",
        "women have made this community worse",
        "competitive play was better before women joined",
        "ranked went downhill when women started playing",
    ]):
        add(rows, seen, f"hate.gender-blame.{index}", "TARGETED_HATE",
            "REPHRASE", text, "gender_identity", True, False,
            "gender blamed for ruining the game")

    for index, text in enumerate([
        "I'm coming to your area",
        "I'm coming to your neighbourhood",
        "I'm coming to your street",
        "I'm coming to your town",
        "I'm coming to your city",
        "I'm on my way to your house",
        "I'm heading to where you live",
        "I'll be in your area soon",
        "I know the area you're in",
        "I'll find you in your neighbourhood",
    ]):
        add(rows, seen, f"threat.location.{index}", "THREAT", "BLOCK", text,
            "none", True, False, "real-world location threat")

    # --- Iteration 12: "ruin every game" degradation. ---
    for index, text in enumerate([
        "you spoil every game you join",
        "you ruin every game you join",
        "you spoil every match",
        "you ruin every match you play",
        "you spoil every round",
        "you ruin every team you join",
        "every game is worse when you join",
        "every match gets worse when you play",
        "you make every game worse",
        "you make every team worse",
        "you always ruin the fun",
        "you always spoil the mood",
    ]):
        add(rows, seen, f"harass.ruin-game.{index}", "TARGETED_HARASSMENT",
            "REPHRASE", text, "other", True, False, "targeted degradation")

    # --- Iteration 13: "finished/dead if I find you" death threats. ---
    for index, text in enumerate([
        "you're finished if I find you",
        "you're finished when I find you",
        "you're done if I find you",
        "you're done when I catch you",
        "you're dead if I find you",
        "you're dead when I catch you",
        "you're over if I find you",
        "you won't make it if I find you",
        "if I find you, you're finished",
        "if I catch you, you're done",
    ]):
        add(rows, seen, f"threat.finished.{index}", "THREAT", "BLOCK", text,
            "none", True, False, "death threat conditional on finding the player")

    # Ambiguous rows are kept out of training by prepare_dataset.py, but are
    # useful for a later context-aware reviewer.
    ambiguous = [
        ("you people are annoying", "unclear group reference"),
        ("those people don't belong here", "unclear target group"),
        ("what a freak", "unclear identity vs behavior"),
        ("that group should leave", "unclear target"),
        ("you know what you are", "implied insult without identity"),
        ("that joke was weird", "unclear whether identity-based"),
    ]
    for index, (text, note) in enumerate(ambiguous):
        add(rows, seen, f"ambiguous.{index}", "NONE", "REVIEW", text,
            "none", False, False, note)

    return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="data/generated.jsonl")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()
    rows = generate(args.seed)
    path = Path(args.out)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
    from collections import Counter
    print(json.dumps({
        "rows": len(rows),
        "labels": dict(Counter(r["label"] for r in rows)),
        "categories": dict(Counter(r["category"] for r in rows)),
        "families": len({r["family"] for r in rows}),
        "output": str(path),
    }, indent=2))


if __name__ == "__main__":
    main()
