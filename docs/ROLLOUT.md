# Rollout

Use this order. Do not skip shadow mode or jump to automatic bans (CleanSpeak has
no ban action).

## 1. Rule-based text checks in shadow mode

- Install with defaults, then set `text.shadow-mode: true` for the initial
  observation period (the shipped default enforces).
- Keep ML disabled.
- Announce that chat may be analysed.
- Have staff use `/cs recent`, `/cs violations` and `/cs show`.
- Record false positives and misses. Test direct violations, altered spellings,
  Unicode, split messages, quotations/reports, harmless swearing, links and
  multiple languages.
- If you use an example/third-party `/tell` adapter, test the actual delivery
  path now. Do not claim `/tell` is protected based on public chat tests.

## 2. ML classification in shadow mode

- Only if you have a local model sidecar and a reason to use it.
- Before promoting a trained model, run `py tools/training/check_gates.py` and
  keep the report with the release. Do not deploy a model that fails a gate.
- Re-run the gates after changing the lexicon, thresholds, challenge set or
  training data.
- Enable `text.ml.enabled: true`, keep `may-block: false`.
- In `RULES_ONLY`, ML is observation-only. Review the `observation` decisions in
  `/cs recent`.
- If you need ML blocking, test `ASYNC_REPLAY` on staging first. It cancels and
  replays messages as unsigned system messages, changing signed chat and
  formatter behavior.

## 3. Review false positives and misses

- Review by category, channel, language and message type.
- Check quotation/reporting cases especially carefully.
- Do not add identity terms as standalone violations.
- Tune lexicons and thresholds; reload and repeat.
- Decide what `REPHRASE`, `REVIEW` and `BLOCK` mean operationally.

## 4. Enable selected text blocks

- Set `text.shadow-mode: false` only when staff are ready.
- Start with the clearest categories (threats/scams) or selected rules.
- Keep a short player notice and an appeal route.
- Text already delivered cannot be unsent; do not promise otherwise.
- Monitor staff alerts and appeals.

## 7. Continue reviewing appeals and performance

- Use `/cs appeals`, `/cs resolve`, `/cs flag` and `/cs reverse`.
- Check `/cs status` for queue drops, timeouts and model availability.
- Watch for model failures and remember the local-rules fallback can miss content.
- Re-test after Paper/EssentialsX/model upgrades.

## Rollback

- Set `text.shadow-mode: true` and `/cs reload` to stop text enforcement.
- Remove or disconnect the model sidecar if needed; text rules remain.
- If a messaging adapter fails, treat its route as unprotected; do not
  claim coverage.

## Avoid

- Automatic server bans or kicks based on a slur/model.
- Blanket identity-word filters.
- Inspecting every command or private plugin message.
- External model services without explicit configuration and player notice.
- Claiming complete bypass protection.
