# Glossary: Polish identifiers → English

File and folder names are in English. Inside the code, function, variable and option names, the keys of logs and JSON
files, the in-game commands (`/walcz`, `/haslo`) and the names of files the code writes at runtime are still Polish —
renaming them would break the saved checkpoints (PyTorch stores weights under the attribute names), the training logs and
the result files. This page translates them.

## Files the code writes or reads at runtime

| Name | Meaning |
|---|---|
| `training/runs/<run>/` | a training run (e.g. `pvp7`) |
| `siec.pt` / `siec_pokaz.pt` | full checkpoint with optimizer / light fp16 copy for the show ("siec" = network, "pokaz" = show) |
| `champion_stage6.pt` | the released stage-6 champion |
| `przed_etapem.pt` | the network from before the current stage (reference for "stage gain") |
| `mistrz_przedmiotow.pt` | stage-5 "item champion" (used for the stage-6 fixed pool) |
| `pula/`, `pula_stala/`, `archiwum/` | opponent pool, fixed pool (anchor), permanent archive |
| `egzamin_wersje/` | copies of the versions that took an exam |
| `log.csv` | training log (columns below) |
| `egzamin.json`, `egzamin6.json`, `ocena_przedmiotow.json`, `ocena_koncowa*.json`, `turniej_obcy.json` | exams (stages 1–5, stage 6), item evaluation, final evaluation, tournament results |
| `historia_martwych.json` | dead-neuron history for the dashboard |
| `training/recordings/lekcje_*.npz`, `walka_*.npy`, `pad_*.npz` | teacher lessons, recorded human fights in the show, gamepad lessons |
| `training/videos/` | recordings from the client (camera/pilot), `dziennik_pilota.csv` = pilot diagnostic log |
| `probka_X.npy` | sample of network inputs written by the show for the dashboard x-ray |
| `PAUZA` | pause flag file (training waits while it exists) |
| `pvpmost_haslo.txt`, `rcon_haslo.txt`, `pilot_haslo.txt` | show join password, RCON password, dev-client password (never in the repository) |
| `pvpmost` | mod id of the server mod ("most" = bridge); `-Dpvpmost.port` sets its bridge port |
| `minecraft-siec` | game directory of the Minecraft installation with the assistant (watched by `game_watcher.py`) |

## Old → new file names

For readers of older notes or logs.

| Old (Polish) | New |
|---|---|
| `uczenie/`, `obce/`, `serwer/`, `serwer2/`, `serwer3/`, `serwer-pokaz/` | `training/`, `third_party/`, `server1/`, `server2/`, `server3/`, `server-show/` |
| `przebiegi/`, `nagrania/`, `wideo/`, `klient_zrzuty/`, `panel_www/` | `runs/`, `recordings/`, `videos/`, `client_dumps/`, `panel_web/` |
| `most.py`, `cechy.py`, `siec.py`, `ucz.py`, `boty.py` | `bridge.py`, `features.py`, `network.py`, `train.py`, `bots.py` |
| `nasladuj.py`, `zbierz_lekcje.py`, `pokaz.py` | `imitate.py`, `collect_lessons.py`, `show.py` |
| `egzamin.py`, `egzamin6.py`, `straznik.py`, `kontrola.py`, `warta.py` | `exam.py`, `exam6.py`, `watchdog.py`, `health_check.py`, `watch_report.py` |
| `ocena_przedmiotow.py`, `ocena_koncowa.py`, `tabela_oceny.py` | `eval_items.py`, `eval_final.py`, `eval_table.py` |
| `turniej_obcy.py`, `turniej_broni.py`, `nowy_etap6.py` | `tournament.py`, `weapon_duel.py`, `new_stage6.py` |
| `klient_siec.py`, `straznik_gry.py`, `arena_klienta.py`, `opoznienie.py` | `assistant.py`, `game_watcher.py`, `client_arena.py`, `delay_proxy.py` |
| `test_etap6.py`, `test_krytykow.py`, `test_mechaniki.py`, `test_predkosci.py`, `nierownosc.py`, `dodaj_gracza.py` | `test_stage6.py`, `test_crits.py`, `test_mechanics.py`, `test_speed.py`, `seat_bias.py`, `add_player.py` |
| `PvpMost.java` (`pl.pvp.most`), `Asystent.java` | `PvpBridge.java` (`pl.pvp.bridge`), `Assistant.java` |
| `pokaz_dla_kolegi.sh`, `zatrzymaj_pokaz.sh`, `asystent_sieci.command` | `show_for_friend.sh`, `stop_show.sh`, `assistant.command` |
| `server.properties.wzor`, `pilot/serwer_testowy/` | `server.properties.template`, `pilot/test_server/` |
| `mistrz_etapu6.pt`, LaunchAgent `pl.pvp.asystent` | `champion_stage6.pt`, `pl.pvp.assistant` |

## Common identifiers

| Polish | English | | Polish | English |
|---|---|---|---|---|
| `siec` | network | | `gracz`, `gracze` | player(s) |
| `klatka`, `areny` | arena (cage), arenas | | `runda` | round |
| `przeciwnik`, `cel` | opponent, target | | `wrog` | enemy |
| `obs`, `obserwacje` | observations | | `akcje`, `akc` | actions |
| `wejscie` | input | | `glowy` | (action) heads |
| `nagroda`, `kara` | reward, penalty | | `remis` | draw |
| `zabic`, `zginal` | kills, died | | `bilans` | damage balance |
| `zadane`, `otrzymane`, `stracone` | dealt, received, lost (HP) | | `wygrane`, `przegrane` | wins, losses |
| `bron` | weapon | | `piesci`, `miecz`, `siekiera` | fists, sword, axe |
| `zbroja` | armor | | `tarcza` | shield |
| `luk`, `strzala` | bow, arrow | | `wedka`, `splawik`, `hak` | fishing rod, bobber, hook |
| `jedzenie`, `jablka`, `steki` | food, apples, steaks | | `bloki` | blocks |
| `wiadro`, `lawa` | bucket, lava | | `mikstura`, `leczenie` | potion, healing |
| `przedmioty`, `zestaw`, `maska` | items, loadout, (item) mask | | `teren` | terrain |
| `celuje`, `celownik` | aiming, crosshair | | `pasek` | attack-strength bar |
| `skok`, `sprint`, `cios`, `kucanie` | jump, sprint, attack, sneak | | `prawy` | right button |
| `obrot` | turn | | `ruch` | movement |
| `pula` | pool | | `aktualizacja`, `akt` | update |
| `kroki` | steps | | `tempo` | tick rate |
| `egzamin` | exam | | `straznik` | watchdog |
| `lekcje` | lessons | | `nauczyciel` | teacher |
| `ozyw_neurony` | revive (dormant) neurons | | `martwe` | dead |
| `pokaz` | show | | `wersja` | version |
| `etap` | stage | | `mistrz` | champion |
| `zysk etapu` | stage gain | | `postep` | progress |
| `lag`, `ping` | lag, ping | | `widok` | view (delayed opponent) |
| `wczytaj`, `zapisz` | load, save | | `dopasuj` | adapt (shape) |

## Command-line options (selection)

| Option | Meaning |
|---|---|
| `--nazwa` | run name (`runs/<name>`) |
| `--wznow` | resume from `siec.pt` |
| `--porty`, `--rcony` | bridge ports, RCON ports |
| `--areny`, `--multi`, `--multi_pvpbot` | arenas per server, 1v3 arenas, 1v3 arenas with pvp-bot |
| `--bronie`, `--przedmioty`, `--lag`, `--etap6` | weapons (stages 2–3), items (stage 5), ping (stage 4+), stage 6 |
| `--kara_remis` | draw penalty |
| `--entropia`, `--entropia_skok`, `--glowy_zachety`, `--wygaszanie` | entropy bonus, extra bonus, which heads, decay |
| `--udzial_pula`, `--udzial_bot` | share of pool / bot arenas |
| `--lekcje`, `--glowy_lekcji`, `--waga_czlowieka` | lesson files, heads they train, lesson weight |
| `--rozklad_botow` | probabilities of 1/2/3 enemies in 1v3 |
| `--teren_plasko`, `--teren_nowy` | probability of a flat round / new terrain |
| `--z_losowaniem` | sample actions (show) |
| `--surowo`, `--bez_spokoju` | assistant: no smoothing / no calm mode |
| `--sekundy`, `--co_ile` | duration, interval |
| `--urzadzenie` | device (`mps`, `cuda`, `cpu`) |

## Log columns (`log.csv`)

`aktualizacja` update · `kroki` steps · `kroki_na_s` steps/s · `zbieranie_s` / `nauka_s` collect / learn seconds ·
`bot_wygrane/przegrane/remisy` vs bot v2 W/L/D · `rund` rounds · `zabic` kills · `na_punkty` won on points ·
`remisy` draws · `dl_rundy` round length · `odl_self` self-play distance · `entropia` entropy · `strata_wartosci` value
loss · `kl`, `clipfrac` · `pula_wygrane/...` vs pool · `ozywione` revived neurons · `skok_proc` / `sprint_proc` jump /
sprint % · `strzaly` arrows · `traf_strzala` arrow hits · `blokady` shield blocks · `zjedzone` eaten · `haki` rod hooks ·
`bloki_post` blocks placed · `lawa`, `leczenie`, `mikstury` lava / healing / potion uses · `v3_*` 1v3 (rounds, W, L, D,
enemies down) · `pb3_*` 1v3 vs three pvp-bots.
