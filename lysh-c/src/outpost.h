/* lysh outpost.h -- lowest pillager outposts (MC 1.19 .. 26.3).
 *
 * Source of truth: the 26.3 jar (Vineflower), cross-checked against an in-game oracle
 * (C:\CODE\outpost-oracle: Minecraft's own Structure.generate over 4096 candidates).
 *
 * -- Placement (structure_set/pillager_outposts.json) --------------------------------
 *   random_spread spacing 32 / separation 8 / salt 165745296, linear spread
 *   frequency 0.2 via legacy_type_1:
 *       setSeed((long)((cx >> 4) ^ ((cz >> 4) << 4)) ^ seed); nextInt(); nextInt(5) == 0
 *   exclusion_zone: no `villages` placement chunk (spacing 34 / sep 8 / salt 10387312)
 *       within 10 chunks (Chebyshev). Positional only -- villages' biomes are NOT checked.
 *
 * -- Shape (JigsawPlacement.addPieces + the outpost templates) ----------------------
 *   rot  = setLargeFeatureSeed(seed, cx, cz).nextInt(4)   (NONE, CW90, CW180, CCW90)
 *   base_plate 16x30x16 placed at (cx*16, 0, cz*16) with that rotation (pivot 0,0,0);
 *   ctr  = ((maxX+minX)/2, (maxZ+minZ)/2)               (Java int division)
 *   Hc   = getFirstFreeHeight(ctr, WORLD_SURFACE_WG)   (pre-carver; water counts)
 *   biome at (ctr.x, Hc, ctr.z) must be in #has_structure/pillager_outpost
 *   base plate box bottom = Hc - 1 (ground level delta 1); the tower sits on it.
 *   3 plate_entry jigsaws (local (0,0,7) (7,0,15) (15,0,8)) each attach a
 *   terrain-matching feature_plate whose box bottom = getFirstFreeHeight(jigsaw x,z).
 *   The three plates never overlap each other horizontally, so each one is placed
 *   unless it leaves the 80-block vertical window around Hc.
 *   Features on a plate must fit inside the plate's own box, so they are never lower.
 *   => lowest piece Y  = min(Hc - 1, H(plate i) for every placed plate)
 *   => StructureStart.getBoundingBox() bottom = lowest piece Y - 12
 *      (Structure.adjustBoundingBox inflates by 12 for beard_thin); this is the box the
 *      pillager spawn override (bounding_box: full) uses.
 */
#ifndef LYSH_OUTPOST_H
#define LYSH_OUTPOST_H

#include <stdint.h>
#include "eval.h"

#define LYSH_OUTPOST_SALT        165745296
#define LYSH_VILLAGE_SALT        10387312
#define LYSH_OUTPOST_BBOX_INFLATE 12

/* Game version -> biome parameter tree (terrain itself is identical for 1.19+). */
enum {
    LYSH_OP_V_1_19     = 0,   /* 1.19 .. 1.19.1   btree18 */
    LYSH_OP_V_1_19_2   = 1,   /* 1.19.2 .. 1.19.3 btree192 */
    LYSH_OP_V_1_19_4   = 2,   /* 1.19.4 .. 1.20.5 btree19 */
    LYSH_OP_V_1_20_6   = 3,   /* 1.20.6 .. 1.21.3 btree20 */
    LYSH_OP_V_1_21_4   = 4,   /* 1.21.4           btree21wd */
    LYSH_OP_V_1_21_5   = 5,   /* 1.21.5 .. 26.1   btree215 */
    LYSH_OP_V_26_2     = 6,   /* 26.2             btree262 */
    LYSH_OP_V_26_3     = 7,   /* 26.3             MC RTree dump (adds dappled_forest; default) */
    LYSH_OP_V_N
};
const char *lysh_outpost_version_name(int v);
int lysh_outpost_version_parse(const char *s);   /* -1 if unknown */

enum {
    LYSH_OP_OK = 0,
    LYSH_OP_REJECT_FREQ,      /* 1-in-5 roll failed */
    LYSH_OP_REJECT_VILLAGE,   /* village placement chunk within 10 chunks */
    LYSH_OP_REJECT_PREFILTER, /* every column is solid at the target Y (exact, cheap) */
    LYSH_OP_REJECT_BIOME,
    LYSH_OP_REJECT_Y,         /* bbox bottom above max_y */
    LYSH_OP_REJECT_N
};

typedef struct {
    int cx, cz, rot;
    int ctr_x, ctr_z;
    int h_center;             /* getFirstFreeHeight at the centre column */
    int entry_x[3], entry_z[3], h_entry[3], plate_ok[3];
    int biome;                /* cubiomes id */
    int base_y;               /* base plate bottom = h_center - 1 */
    int piece_min_y;          /* lowest piece box bottom */
    int bbox_min_y;           /* StructureStart bounding box bottom (piece_min_y - 12) */
    int reject;               /* LYSH_OP_* */
} lysh_outpost_result;

/* Candidate chunk of random_spread placement in region (rx, rz). */
void lysh_outpost_candidate(uint64_t seed, int rx, int rz, int *cx, int *cz);
int  lysh_outpost_freq_ok(uint64_t seed, int cx, int cz);
int  lysh_outpost_village_near(uint64_t seed, int cx, int cz);
int  lysh_outpost_biome_allowed(int biome, int version);

/* Init a search ctx for outposts (wraps lysh_search_ctx_init + version tree). */
void lysh_outpost_ctx_init(lysh_search_ctx *ctx, uint64_t seed, int version);

/* Full evaluation of the outpost candidate in region (rx, rz).
 * max_y: keep only bbox_min_y <= max_y (INT_MAX = no filter; also disables the prefilter). */
void lysh_outpost_eval(lysh_search_ctx *ctx, int version, int rx, int rz, int max_y,
                       lysh_outpost_result *out);

/* ---- Multi-threaded scan session (one noise-stack init per thread per seed) ----
 * band() scans regions [rx0,rx1) x [rz0,rz1); hits (reject == OK) are appended to
 * out->hits (caller buffer, may be NULL); counts[] gets every candidate's reject reason.
 * rows_done is bumped as rows finish so another thread can poll progress. */
typedef struct {
    lysh_outpost_result *hits;
    int hits_cap, hits_written;
    long long counts[LYSH_OP_REJECT_N];
    long long scanned;
    volatile long long rows_done;
} lysh_outpost_scan_out;

typedef struct lysh_outpost_session lysh_outpost_session;
lysh_outpost_session *lysh_outpost_session_open(uint64_t seed, int version, int threads);
void lysh_outpost_session_band(lysh_outpost_session *s, int rx0, int rx1, int rz0, int rz1,
                               int max_y, lysh_outpost_scan_out *out);
void lysh_outpost_session_free(lysh_outpost_session *s);

#endif
