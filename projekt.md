# Regionium – projektállapot és technikai terv

## 1. Cél
Regionium egy Minecraft 26.3 Fabric mod regionális multithreadinghez. Nem Folia és nem MultiPaper. A világ, chunkok, játékosok és entitások közös memóriában maradnak; csak a végrehajtási ownership mozog régiók között. A végső cél chunk-szintű ownership, seamless thread migration, lagoló régió izolálása és nagy mod-kompatibilitás.

## 2. Jelenlegi alap
- Jelenlegi gépen 14 execution region.
- Minden régiónak saját single-thread workerje van: Regionium-Worker-N.
- RegioniumRegion mailboxot, active tick állapotot és ownershiphez kötött worker executiont kezel.
- RegioniumScheduler kezeli a régiókat, ownership registryt, transfer queue-t és chunk lease infrastruktúrát.
- RegioniumChunkLeaseManager a vanilla simulation állapotból követi a szimulációs chunkokat; nincs fix X/Z stripe.
- Player és ServerLevel hosszú távon ugyanahhoz az execution domainhez kell tartozzon.

## 3. Kritikus hiba, amit most megtaláltunk
A piston redstone clock leállt, blokkütésnél furcsa eltűnés jelent meg. Konkrét log:

    Trying to set block entity PistonMovingBlockEntity at BlockPos{x=12, y=91, z=-37}, but state Block{minecraft:air} does not allow it

Ez azt jelenti, hogy a piston moving block entity és az adott block state időben inkonzisztens lett. Ez nem egyszerű piston bug, hanem világállapot-versenyre utal.

## 4. Gyökérok
MC 26.3 ServerLevel.tick() tényleges sorrendje a projekt merged source-a alapján:
1. world border / weather / time és egyéb world preparation
2. scheduled block ticks
3. scheduled fluid ticks
4. raid
5. ServerChunkCache.tick() / chunk ticking
6. block events
7. entities
8. block entities
9. entity management
10. debug synchronizers
11. random-state garbage collection

A korábbi Regionium implementáció ezt külön aszinkron worker-fázisokra bontotta: chunk tick, entity tick és block entity tick külön queue-ként, miközben scheduled ticks és block events részben server threaden maradtak. Így ugyanazt a live világállapotot külön execution domain-ek egyszerre olvashatták/írhatták.

Ez magyarázza a piston hibát, a furcsa block break viselkedést és a korábbi minecart hibát is. Nem bizonyított, hogy ugyanazt az exact vanilla callbacket kétszer futtattuk; a bizonyított probléma a vanilla tick pipeline szétválasztása.

## 5. Deadlock tanulság
Korábban server -> worker -> server deadlock is volt: a server thread workerre várt, a worker pedig vanilla mainThreadProcessor munkára várt. Emiatt a server thread nem várhat vakon workerre, és a vanilla thread-checkek egyszerű átverése sem elegendő.

## 6. Mostani javítás
A multithreading mixineket NEM kapcsoltuk ki. A piston hibának nem az volt a megoldása, hogy visszaállítjuk a teljes vanilla main-thread tickinget; a hiba azt mutatta meg, hogy a jelenlegi partial-phase pipeline-ban a mutáló fázisok rossz időben indulnak.

A jelenlegi javítás:
- a ServerLevel.tick() alatt a regionális mutációk először csak egy adott tickhez tartozó region queue-ba kerülnek;
- a chunk/entity/block-entity fázisok most külön regionális phase-ek;
- egy phase-ben először minden régió queue-ja feltöltődik, utána a közös Regionium ForkJoinPool egyszerre elindíthatja a régiók worker-taskjait;
- a phase release már NEM várja meg az összes régiót; a server thread csak beadja a munkát, majd megy tovább; egy lassú régió saját FIFO backlogot épít, a többi régió ettől függetlenül haladhat;
- a scheduled tick queue továbbra is vanilla server-thread-owned; a region worker által létrehozott új ScheduledTick-et deferred queue gyűjti, és a következő LevelTicks.tick() előtt kerül vissza a vanilla queue-ba;
- a block event queue is server-thread-owned; worker által létrehozott block event deferred queue-ba kerül, és a következő runBlockEvents() előtt kerül vissza a vanilla queue-ba;
- ez fontos redstone/piston javítás: a worker többé nem módosítja közvetlenül a nem thread-safe LevelTicks/ObjectLinkedOpenHashSet queue-kat;
- a régi különálló scheduled-tick callback regionalizálás forrásfájlja megmaradt, de a callbacket már nem mozgatjuk külön workerre;
- a korábbi teljes tick-végi release helyett most a vanilla sorrendhez igazodó phase sorrend van: scheduled ticks -> chunk tick -> block events -> entities -> block entities;
- a worker pool közös ForkJoinPool, de nincs globális phase/tick barrier; két külön régió saját FIFO execution streamben egymástól függetlenül futhat;
- egy régión belül a sorrend megmarad: scheduled tick -> chunk tick -> entity -> block entity;
- a scheduled block/fluid tick callback már szintén Regionium queue-ba kerül, így nem módosítja a live világot a server threadről a worker fázis közben;
- egy regionen belül továbbra is csak egy worker futtatja az adott tick callbackjait.

Ez közvetlenül megszünteti azt a versenyhelyzetet, amelyben a chunk/block-entity worker egyszerre módosította a világot a vanilla runBlockEvents() szerver-threades végrehajtásával.

Fontos: ez a piston race javítása, nem a teljes Regionium tick-architektúra végleges állapota. A következő nagy lépés továbbra is a queue-k valódi regionális ownershipének és a cross-region műveleteknek a megoldása.

## 7. Build és smoke test
Build: ./gradlew :26.3-fabric:build --no-daemon
eredmény: BUILD SUCCESSFUL.

Smoke test: timeout 20s ./gradlew :26.3-fabric:runServer --no-daemon --args='--nogui --port 25575 --universe run/regionium-safe-tick-test'
A szerver 26.3-ként, Fabric + Regionium alatt elindult, 14 régióval, világot generált és Done állapotig eljutott. A folyamatot a timeout állította le; nem crash állította meg. Ez csak startup smoke test, nem redstone correctness proof.

## 8. Végleges architektúra
Nem újabb parallelTickChunks/parallelTickEntities/parallelTickBlockEntities jellegű különálló mixineket kell foltozni.

A cél egy központi Regionium Tick Coordinator: Server -> Regionium Tick Coordinator -> Region 0 / Region 1 / ... / Region N.

Egy regionális tick egyetlen worker-runnable legyen, amely a vanilla logikai sorrendet megtartva kezeli a regionhez tartozó munkát. Egy régióban nem futhat egyszerre két tick. Más régiók ne várjanak rá globális barrierrel.

## 9. Scheduled ticks
Redstone miatt ez kritikus. A korábbi modell, ahol LevelTicks queue server threaden marad, csak a callback kerül workerre, hibás. A queue ownershipét, callbacket és a callback közbeni új schedule műveleteket együtt kell regionalizálni vagy biztonságosan mailboxolni.

## 10. Block events
A ServerLevel block event queue mutable. A végleges modellben az event queue ownershipét és végrehajtását együtt kell kezelni. Cross-region block update közvetlen mutable hozzáférés helyett deferred operation/mailbox legyen.

## 11. Block entities
Block entity mindig a chunk execution ownerével legyen összhangban. Ticker lifecycle, pending registration és removal ugyanazon ownership-modellhez tartozzon. Puszta ticker callback workerre küldése nem elég.

## 12. Entities
Entity, vehicle/passenger lánc, projectile, collision, AI, add/remove és entity management együtt kezelendő. Az entity nem lehet külön execution island a saját chunkjához képest.

## 13. Cross-region műveletek
Nem szabad Region A workerből közvetlenül Region B live chunkját módosítani. A helyes minta: Region A request -> Region B mailbox/deferred operation -> Region B biztonságos execution pont.

## 14. Ownership transfer
Transfer csak biztonságos tick-határon történhet. Az objektum memóriabeli identitása nem változik; csak az execution owner. Player és a hozzá tartozó világ/entitás ownershipének konzisztensnek kell lennie.

## 15. Lag isolation
Ez most első osztályú követelmény. Egy lagoló régió nem állíthatja meg a többi régió tickjét.

A jelenlegi scheduler:
- nem várja meg a korábbi regionális munkát a server tick elején;
- a phase release nem vár a workerökre;
- minden régió saját FIFO queue-t kap;
- ha Region 0-ban van egy lag machine, Region 1 tovább tudja feldolgozni a saját beadott tickjeit;
- a közös ForkJoinPool csak a CPU-erőforrást osztja, nem a tick-progressziót.

A queue jelenleg szándékosan megtartja a beadási sorrendet: scheduled ticks -> chunks -> block events -> entities -> block entities. A végleges Tick Coordinator feladata lesz ezt explicit regionális tick-planba rendezni, bounded backloggal és ownership-aware cross-region mailboxokkal.

## 16. Server thread végső szerepe
Elsősorban network/control, global scheduler, ownership/transfer commit és valóban globális bookkeeping. Nem írhat ugyanabba a live region-owned mutable world state-be, amit közben worker módosít.

## 17. Implementációs sorrend
Phase A – Tick race fix: KÉSZ. A regionális tick callbackok release-e a ServerLevel.tick() végére került, a scheduled tick callbackok is worker queue-ba kerülnek, build és startup smoke test sikeres. A block-event queue is regionalizálásra került: a ServerLevel blokk-event batch workerre kerül, worker által létrehozott új block event pedig deferred queue-ba kerül a következő server-thread gyűjtési pontra. Az ownership-transfer queue elveszett transfer bugja is javítva: a még futó source region transferje nem törlődik.

Phase B – MC 26.3 mapping: FOLYAMATBAN. ServerLevel.tick, ServerChunkCache.tick, tickChunks, LevelTicks, LevelChunkTicks, block events, EntityTickList, block entities, PersistentEntitySectionManager, broadcast és minden mutable queue pontos feltérképezése.

Phase C – Regionium Tick Coordinator: KÖVETKEZŐ. immutable ownership snapshot, regionenként teljes tick plan, egy worker-runnable per region/tick, no duplicate callback, no unbounded queue.

Phase D – Scheduled tick regionalization: queue + callback + schedule mutation együtt.
Phase E – Block event regionalization.
Phase F – Chunk/random tick regionalization.
Phase G – Block entity lifecycle regionalization.
Phase H – Entity + entity management regionalization.
Phase I – Network/player execution visszahozása az aktuális region ownerre.
Phase J – Safe ownership transfer.
Phase K – Stress testing.

## 18. Kötelező stressztesztek
- piston clock több percen át
- repeater/observer clock
- két játékos ugyanazon redstone gépen
- két játékos külön régióban
- folyamatos block break/place
- piston moving blocks
- hopper és más block entityk
- minecart
- mob AI és collision
- projectile
- player/entity teleport
- chunk border crossing
- chunk load/unload
- szándékosan lassú régió és mellette aktív másik régió
- ownership transfer közben mozgó entity/player

## 19. Fontos fejlesztési szabály
Új parallel tick mixin csak akkor kerülhet be, ha előtte pontosan meghatároztuk: ki birtokolja az adatstruktúrát, ki írhatja, ki olvashatja, mikor változik ownership, mi történik cross-region műveletnél, mi történik lag esetén, nincs-e server -> worker -> server várakozási ciklus, megmarad-e a vanilla phase ordering, és lehet-e ugyanazt a mutable objektumot két thread egyszerre módosítani.

## 20. Végső célkép
A Minecraft shared object graph közös marad. A chunk/entity/player objektumokat nem másoljuk threadváltáskor. A Regionium execution ownership layer dönti el, melyik worker tickeli az adott state-et. A thread migration ezért végrehajtási ownership migration, nem world migration.

## 21. Jelenlegi státusz röviden
Kész: region workers, ownership registry, mailbox alap, chunk lease infrastruktúra, transfer queue alap, deadlock-forrás korábbi megszüntetése, a piston race közvetlen fázis-versenyének javítása, scheduled tick callback regionalizálásának első lépése, build és startup smoke test.

Nincs még kész: teljes regionális ServerLevel tick coordinator, a LevelTicks queue valódi ownership-e, block event ownership, block entity lifecycle, entity management, cross-region mailbox semantics, valódi lag-isolated region tick, player packet regionalization, teljes chunk migration, mod compatibility és komoly redstone/multiplayer stress testing.