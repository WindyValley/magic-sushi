# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Magic Sushi is a match-3 puzzle game (7×7 board, 6 sushi types, 60s countdown) for Android 8.0+. It is a complete, offline-capable single-player game built with Kotlin + Jetpack Compose.

## Build & Test Commands

```bash
cd android-app

# Debug APK
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Run unit tests (6 test files / 55 test cases covering engine layer)
./gradlew test

# Run a single test class
./gradlew test --tests "top.windyvalley.magicsushi.engine.BoardEngineTest"

# Install to connected device/emulator
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Requirements:** Android Studio Hedgehog (2023.1.1)+, JDK 17+, Android SDK API 34, Kotlin 1.9.22.

## Architecture

**4-layer horizontal architecture:**

```
UI (Compose Canvas + Composable)    ← Rendering + touch input
State (ViewModel + StateFlow)       ← Immutable GameState snapshots
Logic (pure Kotlin, no Android dep) ← BoardEngine/MatchEngine/CascadeEngine/GravityEngine/ScoreEngine/TimerEngine/AnimationEngine
Storage (SharedPreferences + SoundPool)
```

**Key constraint:** All `engine/` classes are pure Kotlin with zero Android imports — they are unit-testable and UI-independent.

**Touch input (ADR-001):** `PointerInputScope` + `detectDragGestures` in `SushiTile.kt`. Two modes: tap-tap swap (30% sushi-width threshold for drag).

**Rendering (ADR):** `CanvasComposable` (`GameCanvas.kt`) draws the 7×7 board directly on `drawCanvas`. Compose Animation API handles all transitions (FadeOut → Fall → SpawnIn cascade stages).

**State flow:** `GameViewModel` holds a single `MutableStateFlow<GameState>`. UI observes via `collectAsLazyPagingItems()` or `StateFlow.collectAsState()`. Each game action (swap, match, cascade) produces a new immutable `GameState` snapshot.

## Code Map

| Path | Role |
|------|------|
| `engine/Models.kt` | `Board`, `SushiTile`, `Match`, `Direction`, `MatchAxis`, `SushiType` |
| `engine/GameState.kt` | `GameState` data class + `GamePhase` enum (IDLE/PLAYING/PAUSED/GAME_OVER) |
| `engine/BoardEngine.kt` | Board init (avoids initial matches), swap, lock |
| `engine/MatchEngine.kt` | 3-in-a-row detection (horizontal + vertical, L/T shapes) |
| `engine/CascadeEngine.kt` | Recursive match→gravity→match loop (up to 20 rounds) |
| `engine/GravityEngine.kt` | Column gravity fill, null replacement at top |
| `engine/ScoreEngine.kt` | Base score + cascade multiplier |
| `engine/TimerEngine.kt` | Countdown logic, +5s reward per clear (capped at 90s) |
| `engine/AnimationEngine.kt` | Generates `AnimationFrame` sequences for cascade stages |
| `viewmodel/GameViewModel.kt` | Orchestrates engines, owns `MutableStateFlow<GameState>` |
| `ui/canvas/GameCanvas.kt` | `CanvasComposable` 7×7 grid renderer |
| `ui/canvas/SushiTile.kt` | Touch gesture handling (tap/drag detection) |
| `data/PrefsRepository.kt` | High score persistence via SharedPreferences |
| `audio/SoundPlayer.kt` | OGG playback (swap/match/combo/tick) via SoundPool |

## Key Design Decisions (ADR)

- **ADR-001:** PointerInputScope + detectDragGestures for touch
- **ADR-002:** ViewModel + StateFlow for state management
- **ADR-003:** Compose Animation API (no Lottie/ObjectAnimator)
- **ADR-004:** Each elimination resets timer to 60s (not +5s additive)

## Gradle Notes

- Uses Aliyun mirrors in `settings.gradle.kts` (dl.google.com may be unreachable)
- AGP 8.7.3, Kotlin 1.9.22, Compose BOM 2024.02.00
- JVM target 17, minSdk 26, targetSdk 34
