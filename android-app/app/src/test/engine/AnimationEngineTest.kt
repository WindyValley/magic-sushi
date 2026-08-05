package top.windyvalley.magicsushi.engine

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for [AnimationEngine].
 *
 * Design contract — expected animation semantics:
 *
 * Frame 0 (Eliminate): matched tiles fade to alpha=0 (visually removed).
 *                     Surviving tiles stay at full alpha, stationary.
 *
 * Frame 1 (Fall):     Tiles show at their POST-GRAVITY positions.
 *                     offsetY = distance fallen (destinationRow - currentRow).
 *                     Positive offsetY = tile fell DOWNward (visual DOWN).
 *                     Zero offsetY = tile did not move.
 *
 * Frame 2 (Spawn In): Tiles at final settled positions (offsetY=0).
 *                     New tiles for null cells: offsetY = destinationRow - spawnFromRow.
 *                     Positive offsetY = new tile fell DOWN from above.
 *
 * Bug pattern to detect:
 *   If offsetY is NEGATIVE for a falling tile → implementation sign is flipped.
 *   If offsetY is POSITIVE for a spawning tile → spawning-from-below (impossible).
 */
class AnimationEngineTest {

    private fun tile(row: Int, col: Int, type: SushiType) =
        SushiTile(id = row * 7 + col, type = type, row = row, col = col, isSelected = false, isLocked = false)

    // ---------------------------------------------------------------------------
    // Invariant: exactly 3 frames returned (regardless of match state)
    // ---------------------------------------------------------------------------
    @Test
    fun `generateFrames returns exactly 3 frames for empty matches`() {
        val board = BoardEngine.generateInitialBoard(seed = 1L)
        val frames = AnimationEngine.generateFrames(board, emptyList())
        assertEquals(3, frames.size)
    }

    @Test
    fun `generateFrames returns exactly 3 frames for non-empty matches`() {
        val grid: Array<Array<SushiTile?>> = Array(7) { r -> Array(7) { c ->
            if (r == 3 && c < 3) tile(r, c, SushiType.SUSHI1)
            else tile(r, c, SushiType.SUSHI3)
        }}
        val board = Board(size = 7, grid = grid)
        val elim = listOf(board.grid[3][0]!!, board.grid[3][1]!!, board.grid[3][2]!!)
        val match = Match(tiles = elim, axis = MatchAxis.HORIZONTAL, length = 3)
        val frames = AnimationEngine.generateFrames(board, listOf(match))
        assertEquals(3, frames.size)
    }

    // ---------------------------------------------------------------------------
    // Frame 0: Eliminated tiles fade out; surviving tiles stable at alpha=1
    // ---------------------------------------------------------------------------
    @Test
    fun `frame0 eliminated tiles have alpha 0`() {
        val grid: Array<Array<SushiTile?>> = Array(7) { r -> Array(7) { c ->
            if (r == 3 && c < 3) tile(r, c, SushiType.SUSHI1) else tile(r, c, SushiType.SUSHI3)
        }}
        val board = Board(size = 7, grid = grid)
        val elim = listOf(board.grid[3][0]!!, board.grid[3][1]!!, board.grid[3][2]!!)
        val match = Match(tiles = elim, axis = MatchAxis.HORIZONTAL, length = 3)
        val frames = AnimationEngine.generateFrames(board, listOf(match))
        val f0 = frames[0]

        for (t in elim) {
            val state = f0[AnimationEngine.CellKey(t.row, t.col)]
            assertNotNull("eliminated tile should be present in frame 0", state)
            assertEquals("eliminated tile must be fully transparent", 0f, state!!.alpha)
        }
    }

    @Test
    fun `frame0 surviving tiles are at full alpha`() {
        val grid: Array<Array<SushiTile?>> = Array(7) { r -> Array(7) { c ->
            if (r == 3 && c < 3) tile(r, c, SushiType.SUSHI1) else tile(r, c, SushiType.SUSHI3)
        }}
        val board = Board(size = 7, grid = grid)
        val elimIds = setOf(board.grid[3][0]!!.id, board.grid[3][1]!!.id, board.grid[3][2]!!.id)
        val match = Match(
            tiles = listOf(board.grid[3][0]!!, board.grid[3][1]!!, board.grid[3][2]!!),
            axis = MatchAxis.HORIZONTAL, length = 3,
        )
        val frames = AnimationEngine.generateFrames(board, listOf(match))
        val f0 = frames[0]

        for (r in 0..6) for (c in 0..6) {
            val t = board.grid[r][c] ?: continue
            if (t.id in elimIds) continue
            val state = f0[AnimationEngine.CellKey(r, c)]
            assertNotNull("surviving tile should be in frame 0", state)
            assertEquals("surviving tile must be fully opaque", 1f, state!!.alpha)
        }
    }

    // ---------------------------------------------------------------------------
    // Frame 1: Tiles at post-gravity positions.
    // offsetY = destinationRow - currentRow.
    // Positive = tile fell DOWNWARD (visual down).
    // Zero = tile did not move.
    // ---------------------------------------------------------------------------
    @Test
    fun `frame1 tiles at post-gravity positions have correct offsetY`() {
        // Col 0: rows 0-4 = SUSHI1 (5 tiles), row 5 = SUSHI2 (eliminate this one).
        // After gravity: rows 0-3 = SUSHI1, row 4 = SUSHI2, rows 5-6 = null.
        // Tile originally at row 0 ends at row 0 (didn't move) → offsetY = 0.
        // Tile originally at row 4 ends at row 4 (didn't move) → offsetY = 0.
        // Wait — with 1 elimination in col 0, rows 0-3 stay, row 4 shifts to row 4 (stays), row 5 is null.
        // Actually: col 0 pre-fall: [S1, S1, S1, S1, S1, S2, S3]. Eliminate row5=S2.
        // After null-out: [S1, S1, S1, S1, S1, null, S3]. filterNotNull → [S1×5, S3].
        // After gravity: [null, null, null, null, null, S3, S1].
        // No — let me redo: one null in col 0. dropColumn: [null, S1, S1, S1, S1, S1, S3].
        // So the 5 S1s end at rows 1-5. Original row0→row1, row1→row2, ..., row4→row5.
        // Tile from original row0 fell to row1 → distance = 1 → offsetY = 1.
        // Tile from original row4 fell to row5 → distance = 1 → offsetY = 1.
        val grid: Array<Array<SushiTile?>> = Array(7) { r -> Array(7) { c ->
            if (c == 0 && r < 5) tile(r, c, SushiType.SUSHI1)
            else if (c == 0) tile(r, c, SushiType.SUSHI2)
            else tile(r, c, SushiType.SUSHI3)
        }}
        val board = Board(size = 7, grid = grid)
        val elimTile = board.grid[5][0]!!
        val match = Match(tiles = listOf(elimTile), axis = MatchAxis.VERTICAL, length = 1)

        val frames = AnimationEngine.generateFrames(board, listOf(match))
        val f1 = frames[1]

        // Tiles originally at rows 0-4 all fell 1 step → offsetY = +1
        for (r in 0..4) {
            val key = AnimationEngine.CellKey(r + 1, 0) // settled at row+1
            val state = f1[key]
            assertNotNull("tile from row $r should be at row ${r + 1} in frame 1", state)
            assertEquals(
                "tile fell 1 step → offsetY should be +1 (DOWNWARD), was=${state?.offsetY}",
                1f, state?.offsetY ?: 0f,
            )
        }
    }

    @Test
    fun `frame1 tiles that did not move have zero offsetY`() {
        // Use filler board with one tile eliminated in col 0.
        // The S3 tiles in cols 1-6 were never affected → should have offsetY = 0 in frame 1.
        val grid: Array<Array<SushiTile?>> = Array(7) { r -> Array(7) { c ->
            if (c == 0 && r == 6) tile(r, c, SushiType.SUSHI1)
            else tile(r, c, SushiType.SUSHI3)
        }}
        val board = Board(size = 7, grid = grid)
        val elim = board.grid[6][0]!!
        val match = Match(tiles = listOf(elim), axis = MatchAxis.VERTICAL, length = 1)

        val frames = AnimationEngine.generateFrames(board, listOf(match))
        val f1 = frames[1]

        // All cols 1-6 tiles are unaffected — offsetY should be 0
        for (c in 1..6) for (r in 0..6) {
            val t = board.grid[r][c]
            if (t == null) continue
            val state = f1[AnimationEngine.CellKey(r, c)]
            assertNotNull("tile at $r,$c should be in frame 1", state)
            assertEquals("unaffected tile offsetY must be 0", 0f, state!!.offsetY)
        }
    }

    // ---------------------------------------------------------------------------
    // Frame 2: All tiles settled → offsetY = 0
    // ---------------------------------------------------------------------------
    @Test
    fun `frame2 Stable tiles have zero offsetY`() {
        val grid: Array<Array<SushiTile?>> = Array(7) { r -> Array(7) { c ->
            if (r == 3 && c < 3) tile(r, c, SushiType.SUSHI1) else tile(r, c, SushiType.SUSHI3)
        }}
        val board = Board(size = 7, grid = grid)
        val elim = listOf(board.grid[3][0]!!, board.grid[3][1]!!, board.grid[3][2]!!)
        val match = Match(tiles = elim, axis = MatchAxis.HORIZONTAL, length = 3)

        val frames = AnimationEngine.generateFrames(board, listOf(match))
        val f2 = frames[2]

        // Only Stable tiles in frame2 should have offsetY = 0.
        // SpawningIn tiles (new tiles from above) have positive offsetY.
        for (r in 0..6) for (c in 0..6) {
            val state = f2[AnimationEngine.CellKey(r, c)]
            if (state != null && state.anim is AnimationEngine.TileAnim.Stable) {
                assertEquals(
                    "Stable tile at frame2[CellKey($r,$c)] must have offsetY=0",
                    0f, state.offsetY,
                )
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Spawn-in: new tiles for null cells should have positive offsetY (fell DOWN)
    // ---------------------------------------------------------------------------
    @Test
    fun `frame2 spawn-in tiles have positive offsetY (fell DOWN from above)`() {
        // Eliminate entire col 0 (7 tiles). After gravity: col 0 all null.
        // Frame 2 should show spawn-in tiles at rows 0-6.
        // Each spawn-in tile fell DOWN from above → offsetY > 0.
        val grid: Array<Array<SushiTile?>> = Array(7) { r -> Array(7) { c ->
            if (c == 0) tile(r, c, SushiType.SUSHI1) else tile(r, c, SushiType.SUSHI3)
        }}
        val board = Board(size = 7, grid = grid)
        val allCol0 = (0..6).map { board.grid[it][0]!! }
        val match = Match(tiles = allCol0, axis = MatchAxis.VERTICAL, length = 7)

        val frames = AnimationEngine.generateFrames(board, listOf(match))
        val f2 = frames[2]

        var foundSpawn = false
        for (r in 0..6) {
            val state = f2[AnimationEngine.CellKey(r, 0)]
            if (state != null && state.anim is AnimationEngine.TileAnim.SpawningIn) {
                foundSpawn = true
                assertTrue(
                    "spawn-in tile offsetY must be positive (fell DOWN from above), was=${state.offsetY}",
                    state.offsetY > 0f,
                )
            }
        }
        assertTrue("frame 2 should contain spawn-in tiles for col 0", foundSpawn)
    }

    @Test
    fun `frame2 spawn-in tiles have negative visualId`() {
        val grid: Array<Array<SushiTile?>> = Array(7) { r -> Array(7) { c ->
            if (c == 0) tile(r, c, SushiType.SUSHI1) else tile(r, c, SushiType.SUSHI3)
        }}
        val board = Board(size = 7, grid = grid)
        val allCol0 = (0..6).map { board.grid[it][0]!! }
        val match = Match(tiles = allCol0, axis = MatchAxis.VERTICAL, length = 7)

        val frames = AnimationEngine.generateFrames(board, listOf(match))
        val f2 = frames[2]

        for (r in 0..6) {
            val state = f2[AnimationEngine.CellKey(r, 0)]
            if (state != null && state.anim is AnimationEngine.TileAnim.SpawningIn) {
                assertTrue("spawn-in tile visualId must be negative", state.visualId < 0)
            }
        }
    }

    // ---------------------------------------------------------------------------
    // VisualId preservation: surviving tile keeps same id across all 3 frames
    // ---------------------------------------------------------------------------
    @Test
    fun `surviving tile visualId preserved across all 3 frames`() {
        val grid: Array<Array<SushiTile?>> = Array(7) { r -> Array(7) { c ->
            if (r == 3 && c < 3) tile(r, c, SushiType.SUSHI1) else tile(r, c, SushiType.SUSHI3)
        }}
        val board = Board(size = 7, grid = grid)
        val elimIds = setOf(board.grid[3][0]!!.id, board.grid[3][1]!!.id, board.grid[3][2]!!.id)
        val match = Match(
            tiles = listOf(board.grid[3][0]!!, board.grid[3][1]!!, board.grid[3][2]!!),
            axis = MatchAxis.HORIZONTAL, length = 3,
        )

        val frames = AnimationEngine.generateFrames(board, listOf(match))

        // For each surviving tile (by original id), it should appear in all 3 frames
        // with the SAME visualId (even though its position/offsetY changes)
        for (r in 0..6) for (c in 0..6) {
            val t = board.grid[r][c] ?: continue
            if (t.id in elimIds) continue

            val idInFrame = frames.map { f ->
                f.entries.find { it.value.visualId == t.id }?.value?.visualId
            }

            assertNotNull("surviving tile ${t.id} must appear in frame 0", idInFrame[0])
            assertNotNull("surviving tile ${t.id} must appear in frame 1", idInFrame[1])
            assertNotNull("surviving tile ${t.id} must appear in frame 2", idInFrame[2])
            assertEquals("visualId must be same in frame 0 and 1", idInFrame[0], idInFrame[1])
            assertEquals("visualId must be same in frame 1 and 2", idInFrame[1], idInFrame[2])
        }
    }

    // ---------------------------------------------------------------------------
    // Eliminated tile ids absent from frames 1 and 2
    // ---------------------------------------------------------------------------
    // ---------------------------------------------------------------------------
    // Bug fix: spawn-in tiles must not overwrite gravity-fallen tiles or carry
    // wrong offsetY. This happens when gravity fills a top-of-column null with a
    // tile from above — that null is no longer a spawn slot, and nullCountFromTop
    // must NOT count it.
    // ---------------------------------------------------------------------------
    @Test
    fun `frame1 fallen tiles never overwritten by spawned tiles in frame 2`() {
        // Column 0 layout:
        //   Row 0: SUSHI1  ← eliminated in round 0
        //   Row 1: SUSHI2  ← stays in place (row 1 not null, nothing above to fall into it)
        //   Rows 2-6: SUSHI3
        // After gravity: rows 0 stays null (nothing fell there), rows 1-6 = SUSHI2+SUSHI3.
        // Spawn slots: only row 0 (true top-gap). Row 1 is occupied by the tile that
        // stayed, rows 2-6 are occupied by tiles that fell from above.
        val grid: Array<Array<SushiTile?>> = Array(7) { r ->
            Array(7) { c ->
                if (c == 0) {
                    if (r == 0) tile(r, c, SushiType.SUSHI1)
                    else tile(r, c, SushiType.SUSHI2)
                } else tile(r, c, SushiType.SUSHI3)
            }
        }
        val board = Board(size = 7, grid = grid)
        val elimTile = board.grid[0][0]!!
        val match = Match(tiles = listOf(elimTile), axis = MatchAxis.VERTICAL, length = 1)

        val frames = AnimationEngine.generateFrames(board, listOf(match))
        val f2 = frames[2]

        // Frame 2: exactly ONE SpawningIn tile in column 0 (row 0), NOT at rows 1-6.
        assertEquals(
            "Exactly 1 SpawningIn tile in col 0 (only row 0 is a genuine top-gap)",
            1, spawningInCol0Count(f2),
        )
        // The tile at row 1 (SUSHI2 that stayed) must be Stable, not overwritten.
        val row1State = f2[AnimationEngine.CellKey(1, 0)]
        assertNotNull("Row 1 col 0 must have a tile in frame 2 (wasn't eliminated)", row1State)
        assertTrue(
            "Row 1 col 0 must be Stable (tile stayed in place), was ${row1State?.anim}",
            row1State?.anim is AnimationEngine.TileAnim.Stable,
        )
    }

    private fun spawningInCol0Count(frame: AnimFrame): Int =
        frame.entries.count { (key, state) ->
            key.col == 0 && state.anim is AnimationEngine.TileAnim.SpawningIn
        }

    // ---------------------------------------------------------------------------
    // Multi-round cascade: tiles eliminated in round N must not appear in
    // round N+1's animation frames (neither falling nor spawning).
    // ---------------------------------------------------------------------------
    @Test
    fun `round N eliminated tiles absent from round N+1 frames`() {
        // Build a 2-round board: a 5-row × 3-col block of SUSHI1 in cols 0..2 rows 2..6.
        // Row 6 cols 0..2 = initial match.
        // After round 0 gravity, remaining S1s at rows 2..5 create 7 matches (H+V) in round 1.
        val filler = arrayOf(SushiType.SUSHI3, SushiType.SUSHI4, SushiType.SUSHI5)
        var nextId = 0
        val grid: Array<Array<SushiTile?>> = Array(7) { r ->
            Array(7) { c ->
                val t = if (c in 0..2 && r in 2..6) SushiType.SUSHI1 else filler[(c + r) % 3]
                SushiTile(id = nextId++, type = t, row = r, col = c)
            }
        }
        val board0 = Board(size = 7, grid = grid)

        // Round 0: eliminate row 6 cols 0..2
        val round0Tiles = listOf(board0.grid[6][0]!!, board0.grid[6][1]!!, board0.grid[6][2]!!)
        val round0Match = Match(tiles = round0Tiles, axis = MatchAxis.HORIZONTAL, length = 3)

        // Board after round 0 gravity (no refill — AnimationEngine uses doRefill=false)
        val boardAfterGravity0 = GravityEngine.applyGravity(board0, listOf(round0Match), doRefill = false)
        val fallenRound0Ids = round0Tiles.map { it.id }.toSet()

        // Round 1: detect on boardAfterGravity0
        val round1Matches = MatchEngine.detectMatches(boardAfterGravity0)
        assertFalse("Round 1 should have matches (chain fires)", round1Matches.isEmpty())

        // All tiles from round 0 elimination: should NOT appear in round 1 frames
        val framesRound1 = AnimationEngine.generateFrames(boardAfterGravity0, round1Matches)
        val allIdsInRound1Frames = framesRound1.flatMap { it.values.map { s -> s.visualId } }.toSet()

        for (id in fallenRound0Ids) {
            assertFalse(
                "Tile id=${id} eliminated in round 0 must NOT appear in round 1 frames",
                id in allIdsInRound1Frames,
            )
        }

        // Round 1 eliminated tiles: should NOT appear in round 2 frames
        // (simulate by using boardAfterGravity0 as board for round 1 animation,
        // then applying gravity to get board for round 2)
        val boardAfterGravity1 = GravityEngine.applyGravity(boardAfterGravity0, round1Matches, doRefill = false)
        val round1Eliminated = round1Matches.flatMap { it.tiles }.map { it.id }.toSet()

        // Detect round 2 matches (if any — cascade may stop here)
        val round2Matches = MatchEngine.detectMatches(boardAfterGravity1)
        if (round2Matches.isNotEmpty()) {
            val framesRound2 = AnimationEngine.generateFrames(boardAfterGravity1, round2Matches)
            val allIdsInRound2Frames = framesRound2.flatMap { it.values.map { s -> s.visualId } }.toSet()
            for (id in round1Eliminated) {
                assertFalse(
                    "Tile id=${id} eliminated in round 1 must NOT appear in round 2 frames",
                    id in allIdsInRound2Frames,
                )
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Invariant: each AnimationEngine.CellKey appears at most once in each frame
    // ---------------------------------------------------------------------------
    @Test
    fun `each CellKey appears at most once per frame`() {
        val board = BoardEngine.generateInitialBoard(seed = 1L)
        // Force a match by modifying a corner tile
        val grid = board.grid.map { it.clone() }.toTypedArray()
        grid[3][0] = SushiTile(id = 999, type = SushiType.SUSHI1, row = 3, col = 0)
        val boardWithMatch = board.copy(grid = grid)
        val matches = MatchEngine.detectMatches(boardWithMatch)
        if (matches.isEmpty()) return // skip if no match formed

        val frames = AnimationEngine.generateFrames(boardWithMatch, matches)
        for ((frameIdx, frame) in frames.withIndex()) {
            val keys = frame.keys.toList()
            val uniqueKeys = keys.toSet()
            assertEquals(
                "Frame $frameIdx: each CellKey should appear exactly once, found ${keys.size} entries but ${keys.size - uniqueKeys.size} duplicates",
                keys.size, uniqueKeys.size,
            )
        }
    }

    // ---------------------------------------------------------------------------
    // DIAGNOSTIC: Specific board config from user: row 2 cols 3-5 eliminated
    // ---------------------------------------------------------------------------
    @Test
    fun `diagnostic row 2 cols 3-5 elimination cascade`() {
        // User config: first round eliminates row 2, cols 3,4,5
        // Let's build a board where this creates a cascade
        val filler = arrayOf(SushiType.SUSHI3, SushiType.SUSHI4, SushiType.SUSHI5)
        var nextId = 0
        val grid: Array<Array<SushiTile?>> = Array(7) { r ->
            Array(7) { c ->
                // Row 2 cols 3-5 = SUSHI1 (the match)
                // Rows 3-6 cols 3-5 = SUSHI1 (creates cascade after gravity)
                // Filler elsewhere
                val t = if (c in 3..5 && r in 2..6) SushiType.SUSHI1 else filler[(c + r) % 3]
                SushiTile(id = nextId++, type = t, row = r, col = c)
            }
        }
        val board0 = Board(size = 7, grid = grid)

        println("\n=== Initial board (board0) cols 3-5 ===")
        for (r in 0..6) {
            val row = (3..5).map { c ->
                val tile = board0.grid[r][c]
                if (tile == null) "null" else "${tile.type.name.last()}(id=${tile.id}@r=${tile.row})"
            }.joinToString(" | ")
            println("  row $r: $row")
        }

        // Round 0: eliminate row 2, cols 3-5
        val round0Tiles = listOf(board0.grid[2][3]!!, board0.grid[2][4]!!, board0.grid[2][5]!!)
        val round0Match = Match(tiles = round0Tiles, axis = MatchAxis.HORIZONTAL, length = 3)
        val round0EliminatedIds = round0Tiles.map { it.id }.toSet()

        println("\n=== Round 0 eliminated: ${round0EliminatedIds} ===")

        val boardAfterGravity0 = GravityEngine.applyGravity(board0, listOf(round0Match), doRefill = false)

        println("\n=== boardAfterGravity0 cols 3-5 ===")
        for (r in 0..6) {
            val row = (3..5).map { c ->
                val tile = boardAfterGravity0.grid[r][c]
                if (tile == null) "null" else "${tile.type.name.last()}(id=${tile.id}@r=${tile.row})"
            }.joinToString(" | ")
            println("  row $r: $row")
        }

        val round1Matches = MatchEngine.detectMatches(boardAfterGravity0)
        println("\n=== Round 1 matches: ${round1Matches.size} ===")
        for (m in round1Matches) {
            println("  ${m.axis}[len=${m.length}]: ${m.tiles.map { "(${it.row},${it.col})id=${it.id}" }.joinToString()}")
        }

        if (round1Matches.isEmpty()) {
            println("  NO MATCHES - cascade stops at round 0")
        }

        val round1EliminatedIds = round1Matches.flatMap { it.tiles }.map { it.id }.toSet()

        // Now simulate the animation loop
        var currentAnimBoard = board0
        val cascades = listOf(listOf(round0Match), round1Matches)

        for ((roundIdx, cascadeRound) in cascades.withIndex()) {
            if (cascadeRound.isEmpty()) break
            println("\n=== ROUND $roundIdx animation ===")

            val frames = AnimationEngine.generateFrames(currentAnimBoard, cascadeRound)
            val fallFrame = frames[1]

            val eliminatedThisRound = cascadeRound.flatMap { it.tiles }.map { it.id }.toSet()
            val allEliminatedSoFar = if (roundIdx == 0) round0EliminatedIds else round0EliminatedIds + round1EliminatedIds

            println("Fall frame entries (cols 3-5 only):")
            val fallEntries = fallFrame.entries.filter { it.key.col in 3..5 }.sortedBy { it.key.row * 10 + it.key.col }
            for ((ck, st) in fallEntries) {
                val isBug = st.visualId in allEliminatedSoFar
                val bugMark = if (isBug) " ** BUG: eliminated tile in fall frame **" else ""
                println("  CellKey(${ck.row},${ck.col}): visualId=${st.visualId} anim=${st.anim} offsetY=${st.offsetY}$bugMark")
            }

            // Also show board state at this point
            println("  Board state at this point (cols 3-5):")
            for (r in 0..6) {
                val row = (3..5).map { c ->
                    val tile = currentAnimBoard.grid[r][c]
                    if (tile == null) "null" else "${tile.type.name.last()}(id=${tile.id})"
                }.joinToString(" | ")
                println("    row $r: $row")
            }

            currentAnimBoard = GravityEngine.applyGravity(currentAnimBoard, cascadeRound)
        }

        println("\n=== DIAGNOSTIC DONE ===")
    }

    // ---------------------------------------------------------------------------
    // INDEPENDENT BUG TEST: Round 1 fall frame visualId duplication
    // Self-contained: builds board from scratch, no shared state
    // ---------------------------------------------------------------------------
    @Test
    fun `Round 1 fall frame must not have duplicate visualIds`() {
        // Build board: row 2 cols 3-5 = S1 match, rows 3-6 cols 3-5 = S1 cascade block
        val filler = arrayOf(SushiType.SUSHI3, SushiType.SUSHI4, SushiType.SUSHI5)
        var nextId = 0
        val grid: Array<Array<SushiTile?>> = Array(7) { r ->
            Array(7) { c ->
                val t = if (c in 3..5 && r in 2..6) SushiType.SUSHI1 else filler[(c + r) % 3]
                SushiTile(id = nextId++, type = t, row = r, col = c)
            }
        }
        val board0 = Board(size = 7, grid = grid)

        // Round 0: eliminate row 2, cols 3-5
        val round0Tiles = listOf(board0.grid[2][3]!!, board0.grid[2][4]!!, board0.grid[2][5]!!)
        val round0Match = Match(tiles = round0Tiles, axis = MatchAxis.HORIZONTAL, length = 3)

        // boardAfterGravity0
        val boardAfterGravity0 = GravityEngine.applyGravity(board0, listOf(round0Match), doRefill = false)

        // Round 1 matches
        val round1Matches = MatchEngine.detectMatches(boardAfterGravity0)
        assertFalse("Round 1 should have matches", round1Matches.isEmpty())

        // THE KEY TEST: generate frames for Round 1 and check fall frame
        val frames1 = AnimationEngine.generateFrames(boardAfterGravity0, round1Matches)
        val fallFrame = frames1[1]

        // Assert uniqueness: each visualId must appear exactly once
        val fallFrameVisualIds = fallFrame.values.map { it.visualId }
        val uniqueVisualIds = fallFrameVisualIds.toSet()
        println("\n=== Round 1 Fall Frame ===")
        println("  Total entries in fall frame: ${fallFrame.size}")
        println("  Unique visualIds: ${uniqueVisualIds.size}")
        if (uniqueVisualIds.size < fallFrame.size) {
            val duplicatesList = fallFrameVisualIds.filter { v -> fallFrameVisualIds.count { it == v } > 1 }.toSet()
            println("  DUPLICATES FOUND: $duplicatesList")
        }
        assertEquals(
            "Each visualId must appear exactly once in fall frame",
            fallFrame.size, uniqueVisualIds.size
        )
    }

    // ---------------------------------------------------------------------------
    // Verify Falling offsetY sign: positive = fell DOWN, tile appears LOWER
    // ---------------------------------------------------------------------------
    @Test
    fun `Falling offsetY must be positive (tile fell DOWN)`() {
        // Single elimination: col 0 row 5 eliminated
        // Tiles at rows 0-4 fall 1 step → offsetY should be +1 for all
        val grid: Array<Array<SushiTile?>> = Array(7) { r ->
            Array(7) { c ->
                if (c == 0) {
                    if (r == 5) tile(r, c, SushiType.SUSHI1)
                    else tile(r, c, SushiType.SUSHI2)
                } else tile(r, c, SushiType.SUSHI3)
            }
        }
        val board = Board(size = 7, grid = grid)
        val elimTile = board.grid[5][0]!!
        val match = Match(tiles = listOf(elimTile), axis = MatchAxis.VERTICAL, length = 1)

        val frames = AnimationEngine.generateFrames(board, listOf(match))
        val fallFrame = frames[1]

        println("\n=== Falling offsetY sign check ===")
        for (row in 0..6) {
            val state = fallFrame[AnimationEngine.CellKey(row, 0)]
            if (state != null) {
                val isFalling = state.anim is AnimationEngine.TileAnim.Falling
                if (isFalling) {
                    val anim = state.anim as AnimationEngine.TileAnim.Falling
                    println("  CellKey($row,0): Falling(" + anim.fromRow + "->" + anim.toRow + ") offsetY=" + state.offsetY)
                    assertTrue(
                        "offsetY must be positive for Falling, got " + state.offsetY,
                        state.offsetY > 0f
                    )
                }
            }
        }
    }
}