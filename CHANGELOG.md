# Changelog

All notable changes to KMP WorkManager will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [3.0.1] - 2026-06-20

### Fixed

- Document KmpWorker expedited crash fix on API <31 (v3.0.1) (#60) ([`94bd6fc`](https://github.com/brewkits/kmpworkmanager/commit/94bd6fc77508bf7001166a42bb4e1f498a2f98aa))
- Checksum verify should allow extra checksums (#61) ([`809e5c7`](https://github.com/brewkits/kmpworkmanager/commit/809e5c728e6cea2587bc5202838bd38ce6799ea9))

### Tests

- CI-level getForegroundInfo guard + bump install docs to 3.0.1 (#62) ([`a3bfe09`](https://github.com/brewkits/kmpworkmanager/commit/a3bfe09f0c3e867a58941b907044391a75eb23c2))

## [3.0.0] - 2026-06-14

### Added

- Ktor 3 support — release 3.0.0 (closes #33) (#34) ([`74a4009`](https://github.com/brewkits/kmpworkmanager/commit/74a40096f57940e41761041430ae1c0cf86a54f6))
- Real network reachability via NWPathMonitor (closes #40) (#41) ([`cb19179`](https://github.com/brewkits/kmpworkmanager/commit/cb191796efe3cdae70c35c47021ee1bdb2bce017))

### Changed

- Extract Ktor HTTP workers into kmpworkmanager-http (v3.0.0) (#44) ([`d80fa33`](https://github.com/brewkits/kmpworkmanager/commit/d80fa3384904f05cc3779015522ff1ba78022a8e))

### Documentation

- Fix iOS dispatcher setup — master + chain executor handlers (refs #32) ([`3a549fd`](https://github.com/brewkits/kmpworkmanager/commit/3a549fde68a30488c64c32b455d60ccd371fb1fa))
- Refresh logo palette to indigo→violet→pink + soft amber bolt ([`bffb1bb`](https://github.com/brewkits/kmpworkmanager/commit/bffb1bbb5a084d35d30f270c9217ccfca767f48d))

### Fixed

- Repair Common+KSP job and iOS 18 Xcode pin (#36) ([`f6e337d`](https://github.com/brewkits/kmpworkmanager/commit/f6e337da452c214818a0c903032230c09bafc281))
- Stop init job clobbering queue-size counter (data race) (#37) ([`afd5873`](https://github.com/brewkits/kmpworkmanager/commit/afd5873bc6855ca4ddfdd8c11a3162953a87f70c))
- Remove eager queue-counter init that double-counted (flaky tests) (#38) ([`2e067f0`](https://github.com/brewkits/kmpworkmanager/commit/2e067f0e8c3702b3e1c299496c5a601fa5598345))
- Don't auto-launch maintenance job in test env (flaky crash) (#43) ([`3b07c85`](https://github.com/brewkits/kmpworkmanager/commit/3b07c856341f222841c8010526a94448f789b117))

## [2.5.1] - 2026-05-20

### Documentation

- Fix Worker API examples and KSP annotation usage (fixes #32) ([`83bb97c`](https://github.com/brewkits/kmpworkmanager/commit/83bb97ce7d9772da084f762931e1026e4172feef))
- Fix remaining API inconsistencies in platform-setup and examples ([`7ba3c45`](https://github.com/brewkits/kmpworkmanager/commit/7ba3c45bdafcff9b1c3f47c54cc536f6affb8af5))

## [2.5.0] - 2026-05-18

### Fixed

- Throw on directory creation failure and skip flaky CI stress test ([`6105189`](https://github.com/brewkits/kmpworkmanager/commit/61051896ff6dc831750c5824b0cda2940a5c3cc2))
- Skip file protection on queue directories in test mode ([`5284c97`](https://github.com/brewkits/kmpworkmanager/commit/5284c9739ea3ebbd71c66b03c7fc2ab388a20694))
- Correct artifactId replacement and checksum generation timing ([`abb8486`](https://github.com/brewkits/kmpworkmanager/commit/abb848634e0b8fc96dc3c011666fd8f9fc0f5a98))
- Use non-atomic write in test mode to avoid CI failures ([`8f8ee52`](https://github.com/brewkits/kmpworkmanager/commit/8f8ee528df282d6ce82d451d999e53db6f8a14e9))
- 16 P0/P1/P2 bugs from 4 audit passes (v2.5.0 production hardening) ([`c3c6054`](https://github.com/brewkits/kmpworkmanager/commit/c3c6054bc0c91b39305fe91919788b31cd5d343b))

## [2.4.3] - 2026-05-01

### Documentation

- Update v2.4.3 release notes with actual bug fixes and infrastructure changes ([`72e21da`](https://github.com/brewkits/kmpworkmanager/commit/72e21dacd16429e0864d34637e688d3dcf760c34))

### Fixed

- Prevent hardcoded isTestMode and apply missing CI/CD maven config ([`999dd8a`](https://github.com/brewkits/kmpworkmanager/commit/999dd8a13a9707d0f0a53f0aabaf29ce880d418a))
- Apply 12 security and stability fixes, upgrade to Kotlin 2.1.21 ([`8323fbb`](https://github.com/brewkits/kmpworkmanager/commit/8323fbbe427dd1e8dc117d9159610d97797512e9))
- Eliminate shared-field race in concurrent test execution ([`b8f3cd3`](https://github.com/brewkits/kmpworkmanager/commit/b8f3cd3954e5a2deb38e806281ba3cc3e26216c6))
- Serialize K/N test workers to prevent Gradle XML writer race ([`341db77`](https://github.com/brewkits/kmpworkmanager/commit/341db77493ea12f9dfe41a98e15307a08b4574a9))
- Prevent coroutine leak in NativeTaskScheduler causing CI crashes on iOS ([`1f20e3c`](https://github.com/brewkits/kmpworkmanager/commit/1f20e3c9e33a43131e28c18014f4118062cb0318))

### Tests

- Add comprehensive test cases for v2.4.3 improvements ([`99cf5d7`](https://github.com/brewkits/kmpworkmanager/commit/99cf5d7840a88c068854498f5990afdf69271ff8))

## [2.4.2] - 2026-04-28

### Fixed

- Resolve duplicate AlarmReceiver class and improve iOS stress test robustness ([`eb286da`](https://github.com/brewkits/kmpworkmanager/commit/eb286da5d97bb2fce88530af568387e61e06a79c))
- Resolve D8 duplicate class error and stabilize iOS stress test ([`9d76599`](https://github.com/brewkits/kmpworkmanager/commit/9d765991dfe730021be112003d1fd1f7d1b4c95e))
- Finalize DemoAlarmReceiver package and ignore flaky iOS stress test ([`f76910a`](https://github.com/brewkits/kmpworkmanager/commit/f76910af61038f0c31dfea512b9bb6ee78f56ac5))
- Restore periodic task immediate execution and bump to v2.4.2 ([`ec68958`](https://github.com/brewkits/kmpworkmanager/commit/ec68958d50da777fc6c10adaed213df5648ee8a8))
- Resolve periodic task regression and related memory leaks ([`cdf5505`](https://github.com/brewkits/kmpworkmanager/commit/cdf5505a0454bf86c2b6fcde4786e40d2a7c5c23))

### Tests

- Fix flaky tests and CI hangs by isolating storage paths ([`9a1f04a`](https://github.com/brewkits/kmpworkmanager/commit/9a1f04a05669143f5d4f106898502af05f71a5c1))

## [2.4.1] - 2026-04-24

### Added

- Implement internal dispatcher queue for iOS dynamic tasks ([`ca9db14`](https://github.com/brewkits/kmpworkmanager/commit/ca9db1467fa1123507b4d14520b7eb4b3640b83b))
- Release v2.4.1 with iOS dynamic tasks and periodic task improvements ([`4c06482`](https://github.com/brewkits/kmpworkmanager/commit/4c0648295237b6a9f0c2af6779984526f28095f3))

### Documentation

- Add guide and workaround for iOS dynamic task scheduling limitations ([`571d8b7`](https://github.com/brewkits/kmpworkmanager/commit/571d8b7dc8f6aa9f5e5337b406ed3beaaa6b2ea3))

### merge

- Release v2.4.1 - iOS dynamic tasks and periodic task improvements ([`53c69c6`](https://github.com/brewkits/kmpworkmanager/commit/53c69c6bfd7d27b98ff8d42305833837955da860))

## [2.4.0] - 2026-04-16

### Added

- Implement native Kotlin background task handler and bump version to 2.4.0 ([`c49a392`](https://github.com/brewkits/kmpworkmanager/commit/c49a392828feac54dd8407aa257e9a706b176e64))

### Fixed

- Add foregroundServiceType to satisfy Android Lint and Android 14+ requirements ([`c8a1398`](https://github.com/brewkits/kmpworkmanager/commit/c8a13981e2da2ce8e3ebfcce7bca5939d4af06e7))
- Make BufferedIOTest more deterministic on CI using runTest and virtual time ([`420749d`](https://github.com/brewkits/kmpworkmanager/commit/420749dd4731ee262847054051f20314311914a7))
- Make BufferedIOTest and BugFixes_v239_IosTest deterministic on CI ([`a2fb7c3`](https://github.com/brewkits/kmpworkmanager/commit/a2fb7c3e99b03c53d84dc3ed82f2f01e17b343d5))
- Ensure all modules use root staging directory and include javadoc JARs in Maven Central ZIP ([`cb8020f`](https://github.com/brewkits/kmpworkmanager/commit/cb8020fc65ef504f60cfb6ea4478f39074670ea3))
- Fix Gradle sign/publish task ordering and javadoc jar registration ([`48c8d09`](https://github.com/brewkits/kmpworkmanager/commit/48c8d09485476aaf96abf01cff7ab01683c9eee7))
- Resolve Swift type ambiguity by unifying background engine and removing duplicates in sample app ([`2a4c560`](https://github.com/brewkits/kmpworkmanager/commit/2a4c56099549b4a1ddfadb9f1130c964012a33e3))

### Tests

- Add comprehensive security, performance, and stress tests for v2.4.0 release ([`f0a99c0`](https://github.com/brewkits/kmpworkmanager/commit/f0a99c0c7c68a75caf5fbc796e545dedeb148cb0))
- Fix CI stability by removing redundant tests and properly synchronizing coroutines ([`c427847`](https://github.com/brewkits/kmpworkmanager/commit/c4278473eebb19cf0761ef0db003dd6761e96b2f))

## [2.3.8] - 2026-04-08

### Added

- Comprehensive test suite and documentation improvements ([`b4435fa`](https://github.com/brewkits/kmpworkmanager/commit/b4435fa71bbac3fc1d38baa445d2a5f32eaa4711))
- Release v3.0.0 - Major performance and API improvements ([`677bd39`](https://github.com/brewkits/kmpworkmanager/commit/677bd399c77bc38e33a27b1ef111fe93a91d7464))
- Release v4.0.0 - Worker factory pattern and extensibility improvements ([`8afd8e5`](https://github.com/brewkits/kmpworkmanager/commit/8afd8e5d93a0025124a6f579785c0dc2133504dd))
- Add Event Persistence System design and interface ([`c867d10`](https://github.com/brewkits/kmpworkmanager/commit/c867d10069dc75668ab1d2425e2e066da7ce7a9c))
- Implement Event Persistence System with file-based storage ([`1b2fc7b`](https://github.com/brewkits/kmpworkmanager/commit/1b2fc7b0af3c58ddfbd6924308cd41aa1a2b3c82))
- Integrate Event Persistence with TaskEventBus and Koin ([`a42bcb7`](https://github.com/brewkits/kmpworkmanager/commit/a42bcb737124fa204053a9cef69343bbe98846c8))
- Implement iOS chain state restoration with progress tracking ([`51032b2`](https://github.com/brewkits/kmpworkmanager/commit/51032b2c89bfb0aabf4e954889fa279603cf2d4c))
- Add Windowed trigger support and worker progress tracking ([`b7b0efe`](https://github.com/brewkits/kmpworkmanager/commit/b7b0efe2dcccb4d0b0e40be46f68dd4e2a5ded0a))
- Add Maven Central publishing automation ([`bcfc4e4`](https://github.com/brewkits/kmpworkmanager/commit/bcfc4e4c0a53cc182c7915a4038bd421f993868d))
- Add iOS support for built-in workers ([`6111ee8`](https://github.com/brewkits/kmpworkmanager/commit/6111ee8e4ec2ff47337bd0386b79d174160835a7))
- Add WorkerResult API for data return from workers ([`9a19303`](https://github.com/brewkits/kmpworkmanager/commit/9a19303f93d3fcf75e987cd19f7f46f1a3d269a0))
- Complete built-in workers migration to WorkerResult ([`32018bc`](https://github.com/brewkits/kmpworkmanager/commit/32018bca0b5aa0d416ab62c060797e3ce3acdd80))
- TelemetryHook, TaskPriority, Battery Guard ([`1b365ea`](https://github.com/brewkits/kmpworkmanager/commit/1b365ea9f1888973e5213cb5a949de4b12211618))
- ExecutionHistory + KSP BGTask ID validation ([`ae540eb`](https://github.com/brewkits/kmpworkmanager/commit/ae540eb1b457b4226ce8906956d26c57df4ded6d))

### Changed

- Change package name from com.example.kmpworkmanagerv2 to io.kmp.taskmanager.sample ([`99cf9a4`](https://github.com/brewkits/kmpworkmanager/commit/99cf9a4c75b4aa5d15d3ac275a3b53ac510b636f))
- **BREAKING** Rename kmptaskmanager to kmpworker ([`3dbdc42`](https://github.com/brewkits/kmpworkmanager/commit/3dbdc424f3721081f1701fd33155a0b672fb8c0e))
- **BREAKING** Rename taskmanager to worker throughout codebase ([`21647ec`](https://github.com/brewkits/kmpworkmanager/commit/21647ecb3e771afa9ec14316963b1c7b1812cf00))
- Rename project from 'KMP Worker' to 'KMP WorkManager' and remove V2 suffix ([`57fdecc`](https://github.com/brewkits/kmpworkmanager/commit/57fdecca20b8776d42882d5cb3de551413817c94))
- **BREAKING** Change group ID from io.brewkits to dev.brewkits ([`2b3dd75`](https://github.com/brewkits/kmpworkmanager/commit/2b3dd756cb5294d85e46154d4dc427a70261cd4a))
- Update sample app to v2.3.0 WorkerResult API ([`9ba5d3c`](https://github.com/brewkits/kmpworkmanager/commit/9ba5d3c9bda9ea0607f6c8118cb39777b3764b73))

### Documentation

- Optimize README structure (543→420 lines) ([`e71850e`](https://github.com/brewkits/kmpworkmanager/commit/e71850ef1cbc7ff1b1a7d2aafbf0b059acdf780a))
- Refine README formatting and emoji usage ([`05a1776`](https://github.com/brewkits/kmpworkmanager/commit/05a177680debddaba398d93bdceb5ecd294190c8))
- Rewrite README to be more professional ([`ff0f5c2`](https://github.com/brewkits/kmpworkmanager/commit/ff0f5c2359febd582ebb7874f1aa1574a65e3349))
- Update old package name references ([`927c2e7`](https://github.com/brewkits/kmpworkmanager/commit/927c2e727add98aa53d83fd0c7cfa6fe838a0268))
- Update branding to Brewkits organization ([`15ddfa8`](https://github.com/brewkits/kmpworkmanager/commit/15ddfa83574ed10fc674e693a1ef5d850d84fdbd))
- Add comprehensive test plan for future improvements ([`a4f285c`](https://github.com/brewkits/kmpworkmanager/commit/a4f285ccf4b82e87576de382bbfcdfc6a1d13629))
- Complete final audit - Update all remaining "KMP Worker" references to "KMP WorkManager" ([`5635fcc`](https://github.com/brewkits/kmpworkmanager/commit/5635fcc5c95af7f80cd96694d341e153214d72a4))
- Add comprehensive research analysis and roadmap for v1.1.0 ([`fc4d2ab`](https://github.com/brewkits/kmpworkmanager/commit/fc4d2abccd1c2e69294a979beef357265274f83f))
- Add comprehensive iOS limitations documentation and Android-only API annotations ([`f60ab92`](https://github.com/brewkits/kmpworkmanager/commit/f60ab92d5a098768acbe6302d5c9ec01565dd099))
- Rebrand as 'KMP Worker - Enterprise-grade Background Manager' ([`12e9689`](https://github.com/brewkits/kmpworkmanager/commit/12e96897805efe0906c8ac38b0c7054dd4b527fc))
- Update DEMO_GUIDE with enterprise features and v1.1.0 capabilities ([`b485709`](https://github.com/brewkits/kmpworkmanager/commit/b485709d30343a06f193626f56fbabfa44ab2c9f))
- Add comprehensive publish summary and next steps ([`141344a`](https://github.com/brewkits/kmpworkmanager/commit/141344a71036fd1e9bd009f62bcb52914579ac46))
- Add comprehensive ROADMAP and DEPRECATED README ([`b26b99c`](https://github.com/brewkits/kmpworkmanager/commit/b26b99c8f407e513766379417a061bb74fc6d265))
- Update roadmap versions and improve gitignore ([`330eb0b`](https://github.com/brewkits/kmpworkmanager/commit/330eb0b1e06715335c5387270b3bbfba3fe32f39))
- Clarify task replacement behavior in demo UI ([`4c54152`](https://github.com/brewkits/kmpworkmanager/commit/4c5415222ec323855ebedf517db3b6f59d4e8d12))
- Clean up internal documentation files ([`6a1156c`](https://github.com/brewkits/kmpworkmanager/commit/6a1156c6b7d98791b3b6dd928a7044d8b85225cb))
- Update README with v2.1.2 release and correct roadmap versions ([`fe994f5`](https://github.com/brewkits/kmpworkmanager/commit/fe994f5e36ea8df86ff7d025e35dee03297be0c7))
- Add content-uri-task to Info.plist and document iOS Simulator limitations ([`900aea3`](https://github.com/brewkits/kmpworkmanager/commit/900aea3b91e9a47c74b8ff585678b79fb3c4f55e))
- Add comprehensive GPG signing setup guide for Maven Central ([`632d078`](https://github.com/brewkits/kmpworkmanager/commit/632d078bd5cee7631bdd02f446a5a524aa652a42))
- Update README to v2.1.2 and add dev.to article ([`1a1581e`](https://github.com/brewkits/kmpworkmanager/commit/1a1581ee66450922470e039a5b737103b2d0a0f4))
- Add hero cover image to README ([`5be7495`](https://github.com/brewkits/kmpworkmanager/commit/5be7495b804faae315cd5ed54d937979b2ca71ee))
- Bust GitHub image cache for banner ([`c3406d7`](https://github.com/brewkits/kmpworkmanager/commit/c3406d7feaac6c089f0b166bdd27f90eef479f6d))
- Add comprehensive KSP & Annotation guide ([`d8a640d`](https://github.com/brewkits/kmpworkmanager/commit/d8a640da482011bebef2abffd549458030020dbd))
- Convert KSP guide to English ([`1d464a4`](https://github.com/brewkits/kmpworkmanager/commit/1d464a4474d6596936c3f0cb5f9001d154e3bb32))
- Update README with realistic content and add release documentation ([`d467294`](https://github.com/brewkits/kmpworkmanager/commit/d4672943a95ac1ecd74372025ecaa0fe5a67659e))
- Update documents ([`6ed31c1`](https://github.com/brewkits/kmpworkmanager/commit/6ed31c1250c6da6f03830f72a6bac7ac8947271b))
- Modernize README with professional formatting and improved structure ([`c53f20d`](https://github.com/brewkits/kmpworkmanager/commit/c53f20d04a6b73acd0c5e5ed3d482563ea1432b4))
- Fix iOS initialization documentation inconsistencies (#15) ([`867a12e`](https://github.com/brewkits/kmpworkmanager/commit/867a12e64ec13201cf061b55e222b969d6e42da6))
- Rewrite README for developer audience ([`564c63c`](https://github.com/brewkits/kmpworkmanager/commit/564c63cf258c240dc3da99db452cdd3760591d6e))

### Fix

- Address compiler warnings and deprecations in ComposeApp ([`fa96e21`](https://github.com/brewkits/kmpworkmanager/commit/fa96e21602dd90de67d12b50c5053488e46bb19b))
- Resolve DEBUG macro conflict and update Swift calls to async/await ([`d9020f7`](https://github.com/brewkits/kmpworkmanager/commit/d9020f7f77bc4e3070733d352e22438ae634d6c9))
- Resolve TaskEventBusTest, update docs, correct worker return types, and document KSP/iOS demo build issues ([`a611fd7`](https://github.com/brewkits/kmpworkmanager/commit/a611fd7b4fbf5723317c010518a45c724fa2d7fe))

### Fixed

- Keep original contact email vietnguyentuan@gmail.com ([`6e169c2`](https://github.com/brewkits/kmpworkmanager/commit/6e169c29831faf997e1528d6960a3b33ab056491))
- Increase EventBus replay buffer from 0 to 5 events ([`81b95ac`](https://github.com/brewkits/kmpworkmanager/commit/81b95acbd0046e20c018c8dd19c60a9919dc2d40))
- Add exact-reminder to iOS Info.plist BGTaskSchedulerPermittedIdentifiers ([`d5c2428`](https://github.com/brewkits/kmpworkmanager/commit/d5c242865aaa5bef4e2ad417bfb39ed9770353df))
- Replace dynamic task ID with fixed ID for iOS compatibility ([`1f15e3f`](https://github.com/brewkits/kmpworkmanager/commit/1f15e3f0896f9e30582c364539fbbf4fdff37b77))
- Critical fixes for v2.1.0 production release ([`40ee80b`](https://github.com/brewkits/kmpworkmanager/commit/40ee80b42aa61cab84f5c463f924f28974ea6eaf))
- Critical iOS stability fixes for v2.1.2 ([`209205c`](https://github.com/brewkits/kmpworkmanager/commit/209205c55cb24b076e163c937727a1e0d1e6ef27))
- Add configurable foreground service type for Android 14+ ([`647be81`](https://github.com/brewkits/kmpworkmanager/commit/647be8193eb35e7e1b5d56fe97b79e1dc1e4564e))
- Update iOS app to use async/await API and correct KotlinInt properties ([`2d6567b`](https://github.com/brewkits/kmpworkmanager/commit/2d6567b98ee59d9355a490f568e96fb6b0366560))
- Use NSNumber conversion for KotlinInt in Swift ([`b469800`](https://github.com/brewkits/kmpworkmanager/commit/b469800727b597c1572e0ac70f0f1330ecfedf8f))
- Fix run GitHub action ([`e741bde`](https://github.com/brewkits/kmpworkmanager/commit/e741bdebb29ffcbc12fa852e2068ab567c22aa91))
- Fix bug for Github action ([`d4fb221`](https://github.com/brewkits/kmpworkmanager/commit/d4fb221d7a84b0523fae94c5ffa4acfba78001de))
- IOS simulator detection and chain executor improvements (#10) ([`8351c54`](https://github.com/brewkits/kmpworkmanager/commit/8351c54607787eefcff246d4943a9f0cd9143b95))
- IOS compilation issues for v2.2.2 Maven release ([`4e546cd`](https://github.com/brewkits/kmpworkmanager/commit/4e546cd607de77b9d451fb1b1b4a496f203dfb41))
- Resolve compilation errors ([`4bb4b51`](https://github.com/brewkits/kmpworkmanager/commit/4bb4b51b738b030dcb18930381e5c0eb36865408))
- Complete remaining implementation issues ([`317519d`](https://github.com/brewkits/kmpworkmanager/commit/317519d2cce94c2f4e8b93baa529f6540cf8b958))
- Update iOS demo app to use WorkerResult API ([`666201b`](https://github.com/brewkits/kmpworkmanager/commit/666201b8f770d87e8357a4ea06f55e72b3e36fdc))
- Update iOS app to use simplified WorkerResult type checking ([`231ff3e`](https://github.com/brewkits/kmpworkmanager/commit/231ff3ef0389f538b2629be5c2598d1027584afa))
- V2.3.3 — WorkManager 2.10.0+ compat, chain heavy routing, notification i18n (#14) ([`50fb143`](https://github.com/brewkits/kmpworkmanager/commit/50fb14351e61a636b13e4e635328e22ad666a5a9))
- V2.3.5 — bug fixes, test coverage, code cleanup ([`ea7010b`](https://github.com/brewkits/kmpworkmanager/commit/ea7010baed320bdfc7c14ccf21712d7d91ce9b51))
- V2.3.5 — bug fixes, test coverage, code cleanup ([`dc50d78`](https://github.com/brewkits/kmpworkmanager/commit/dc50d78c47d358dcdd287a482e1867c6c8ec80f5))
- V2.3.6 — 10 critical bug fixes across iOS and Android ([`ca43ecb`](https://github.com/brewkits/kmpworkmanager/commit/ca43ecb8ddafe83752b4c76950540123f14055ec))
- Address audit findings on iOS chain executor and storage (fix/audit-bugs) ([`0e7a6d0`](https://github.com/brewkits/kmpworkmanager/commit/0e7a6d018f1f849665b76622d92a36e58edb23f2))
- Comprehensive audit fixes — silent failures, diagnostics, security, API safety ([`3009e82`](https://github.com/brewkits/kmpworkmanager/commit/3009e823f02129d788362f50cad8ca1876f95685))
- V2.3.7 — close Android/iOS feature parity gaps ([`90859d5`](https://github.com/brewkits/kmpworkmanager/commit/90859d545d23fe332af83832748e9c36888b8533))
- V2.3.7 — stability hardening and crash prevention ([`6e2d8eb`](https://github.com/brewkits/kmpworkmanager/commit/6e2d8eb5ee2463119b52450f3779c6c1810901cf))
- V2.3.7 — code audit fixes across Android, iOS, and common ([`ab41dd0`](https://github.com/brewkits/kmpworkmanager/commit/ab41dd0ab01d8cc58cb3cd61626470999925a46b))
- V2.3.7 — address 4 architectural issues from code review ([`58f0b7e`](https://github.com/brewkits/kmpworkmanager/commit/58f0b7e7174df33b4d2d69e1b101ceefe14cc91b))
- V2.3.7 — clean up deprecated API, non-nullable factory, flush docs ([`f455d11`](https://github.com/brewkits/kmpworkmanager/commit/f455d118d539c8e901ce3a66ce8e1807e62ac67d))
- V2.3.7 — full audit fixes, streaming upload, QA test suite ([`d4811ee`](https://github.com/brewkits/kmpworkmanager/commit/d4811eeb58b41a0daf63817b821eb2415f441096))
- Wire built-in workers for upload/download demo on Android ([`ffcbd37`](https://github.com/brewkits/kmpworkmanager/commit/ffcbd37922a542257f17b9d982313af5467ceed3))
- Register all sample workers in DemoWorkerFactory ([`6e203bc`](https://github.com/brewkits/kmpworkmanager/commit/6e203bc1044c7a6dacc9b2183b9c41cfe2de8510))
- BuiltinWorkerRegistry returns null for unknown workers ([`e87fd22`](https://github.com/brewkits/kmpworkmanager/commit/e87fd222c435c0edccc54c30e441f665cd50b06e))
- SSRF UserInfo bypass, clearThrottle leak, dead dataClass field ([`08af382`](https://github.com/brewkits/kmpworkmanager/commit/08af38269104ffaf74977e67ec17c365bc85260f))
- Resolve VERSION_NAME NPE on CI ([`9b88d72`](https://github.com/brewkits/kmpworkmanager/commit/9b88d7223d1f74408ea2a8f86f99c2cfd5decd54))
- Inject all required gradle.properties for CI build ([`7ce0400`](https://github.com/brewkits/kmpworkmanager/commit/7ce04006f6136d7ab239263acd81d02d7ba6ff6a))
- Make testBufferConsistency deterministic on CI ([`86a7cd9`](https://github.com/brewkits/kmpworkmanager/commit/86a7cd9fd18cd59ee58851c7e14ae683e0f2e528))
- Add missing AndroidManifest.xml to kmpworker library ([`cbf457d`](https://github.com/brewkits/kmpworkmanager/commit/cbf457d8ea249c535ff16453c0bea1a936acfeed))
- V2.3.8 edge-case hardening — queue safety, stale locks, security ([`60d9f24`](https://github.com/brewkits/kmpworkmanager/commit/60d9f24058f5aaae8c4961973fabea3887993b56))
- Resolve iOS simulator demo chain freezing and zip compression fallback ([`1d8135b`](https://github.com/brewkits/kmpworkmanager/commit/1d8135b3aa0cb12e5007c34b7b6caa6627804931))

### Tests

- Add comprehensive iOS unit tests for stability ([`b1fff38`](https://github.com/brewkits/kmpworkmanager/commit/b1fff3808b01333ef4eb2386dc7e87abe8dfe743))
- Add comprehensive WorkerResult tests and built-in worker chain demos ([`5d9eb95`](https://github.com/brewkits/kmpworkmanager/commit/5d9eb955365ef18afb5552c8a96711e4d2a5acdb))

### add

- For build ios xframe ([`f27e055`](https://github.com/brewkits/kmpworkmanager/commit/f27e055c94338ac338ee27748f9039e1a5d12174))

### chore

- **BREAKING** Migrate to brewkits/kmp_worker organization ([`e9014a4`](https://github.com/brewkits/kmpworkmanager/commit/e9014a4934055d24be85e5bb76d7cd5e4fedd909))

### ref

- Update lib version ([`7c88b6a`](https://github.com/brewkits/kmpworkmanager/commit/7c88b6ac69436c041cd0e3bc2538273aa9ca5b58))
- V2.1.0 Phase 1 Day 1 Completion ([`e950328`](https://github.com/brewkits/kmpworkmanager/commit/e950328f25a02feabb6b9af563b6304015ec3ee3))

### release

- **BREAKING** Version 2.0.0 - Package namespace migration ([`54d3b67`](https://github.com/brewkits/kmpworkmanager/commit/54d3b67b373f7c204c8de2f21891fa3d69a246c6))


