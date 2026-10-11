/* lysh outpost.c -- see outpost.h for the derivation and sources. */
#include "outpost.h"

#include <limits.h>
#include <string.h>

#include "structure.h"

static const char *const VERSION_NAMES[LYSH_OP_V_N] = {
    "1.19~1.19.1", "1.19.2~1.19.3", "1.19.4~1.20.5", "1.20.6~1.21.3",
    "1.21.4", "1.21.5~26.1", "26.2", "26.3"
};
static const int VERSION_TREE[LYSH_OP_V_N] = {
    LYSH_BIOME_TREE_1_18, LYSH_BIOME_TREE_1_19_2, LYSH_BIOME_TREE_1_19_4,
    LYSH_BIOME_TREE_1_20_6, LYSH_BIOME_TREE_1_21_4, LYSH_BIOME_TREE_1_21_5,
    LYSH_BIOME_TREE_26_2_MC, LYSH_BIOME_TREE_26_3
};

const char *lysh_outpost_version_name(int v) {
    return (v >= 0 && v < LYSH_OP_V_N) ? VERSION_NAMES[v] : "?";
}

int lysh_outpost_version_parse(const char *s) {
    for (int v = 0; v < LYSH_OP_V_N; v++)
        if (strcmp(s, VERSION_NAMES[v]) == 0) return v;
    return -1;
}

/* RandomSpreadStructurePlacement.getPotentialStructureChunk, linear spread. */
static void random_spread(uint64_t seed, int rx, int rz, int spacing, int separation, int salt,
                          int *cx, int *cz) {
    lysh_lcg r;
    lysh_set_region_seed(&r, seed, rx, rz, salt);   /* setLargeFeatureWithSalt */
    int j = lysh_lcg_next_int_bound(&r, spacing - separation);
    int k = lysh_lcg_next_int_bound(&r, spacing - separation);
    *cx = rx * spacing + j;
    *cz = rz * spacing + k;
}

static int floor_div(int a, int b) { int q = a / b; return (a % b != 0 && ((a < 0) != (b < 0))) ? q - 1 : q; }

void lysh_outpost_candidate(uint64_t seed, int rx, int rz, int *cx, int *cz) {
    random_spread(seed, rx, rz, 32, 8, LYSH_OUTPOST_SALT, cx, cz);
}

int lysh_outpost_freq_ok(uint64_t seed, int cx, int cz) {
    /* legacyPillagerOutpostReducer: int math, then widened (sign-extended) to long */
    int32_t mix = (int32_t)((uint32_t)(cx >> 4) ^ ((uint32_t)(cz >> 4) << 4));
    lysh_lcg r;
    lysh_lcg_set_seed(&r, (uint64_t)(int64_t)mix ^ seed);
    lysh_lcg_next_bits(&r, 32);
    return lysh_lcg_next_int_bound(&r, 5) == 0;
}

int lysh_outpost_village_near(uint64_t seed, int cx, int cz) {
    for (int rx = floor_div(cx - 10, 34); rx <= floor_div(cx + 10, 34); rx++)
        for (int rz = floor_div(cz - 10, 34); rz <= floor_div(cz + 10, 34); rz++) {
            int vx, vz;
            random_spread(seed, rx, rz, 34, 8, LYSH_VILLAGE_SALT, &vx, &vz);
            if (vx >= cx - 10 && vx <= cx + 10 && vz >= cz - 10 && vz <= cz + 10) return 1;
        }
    return 0;
}

/* #has_structure/pillager_outpost (cubiomes ids): desert plains savanna snowy_plains
 * taiga grove + #is_mountain (meadow frozen/jagged/stony_peaks snowy_slopes cherry_grove). */
int lysh_outpost_biome_allowed(int biome, int version) {
    switch (biome) {
        case 1: case 2: case 5: case 12: case 35:     /* plains desert taiga snowy_plains savanna */
        case 177: case 178: case 179: case 180: case 181: case 182:
            return 1;
        case 185:                                     /* cherry_grove: 1.20+ */
            return version >= LYSH_OP_V_1_19_4;
        default:
            return 0;
    }
}

void lysh_outpost_ctx_init(lysh_search_ctx *ctx, uint64_t seed, int version) {
    lysh_phase1_opts opts;
    memset(&opts, 0, sizeof(opts));
    opts.pre_26_2 = version < LYSH_OP_V_26_2;
    lysh_search_ctx_init(ctx, seed, &opts, -40);
    lysh_biome_init(&ctx->biome, seed, 0, VERSION_TREE[version]);
}

/* StructureTemplate.transform with pivot (0,0,0), rotation index = Rotation.ordinal(). */
static void rot_xz(int rot, int x, int z, int *ox, int *oz) {
    switch (rot) {
        case 1:  *ox = -z; *oz =  x; break;   /* CLOCKWISE_90 */
        case 2:  *ox = -x; *oz = -z; break;   /* CLOCKWISE_180 */
        case 3:  *ox =  z; *oz = -x; break;   /* COUNTERCLOCKWISE_90 */
        default: *ox =  x; *oz =  z; break;
    }
}

/* Cheap exact test: block at (x, y, z) is solid before carving => getFirstFreeHeight > y. */
static int solid_at(lysh_search_ctx *ctx, int x, int y, int z) {
    return lysh_phase2_density_at(&ctx->p2, x, y, z) > 0.0;
}

void lysh_outpost_eval(lysh_search_ctx *ctx, int version, int rx, int rz, int max_y,
                       lysh_outpost_result *out) {
    memset(out, 0, sizeof(*out));
    uint64_t seed = ctx->seed;
    lysh_outpost_candidate(seed, rx, rz, &out->cx, &out->cz);
    if (!lysh_outpost_freq_ok(seed, out->cx, out->cz)) { out->reject = LYSH_OP_REJECT_FREQ; return; }
    if (lysh_outpost_village_near(seed, out->cx, out->cz)) { out->reject = LYSH_OP_REJECT_VILLAGE; return; }

    out->rot = lysh_hut_orientation(seed, out->cx * 16, out->cz * 16);  /* setLargeFeatureSeed + nextInt(4) */
    int px = out->cx * 16, pz = out->cz * 16;
    int ax, az, bx, bz;
    rot_xz(out->rot, 0, 0, &ax, &az);
    rot_xz(out->rot, 15, 15, &bx, &bz);
    int min_x = px + (ax < bx ? ax : bx), max_x = px + (ax < bx ? bx : ax);
    int min_z = pz + (az < bz ? az : bz), max_z = pz + (az < bz ? bz : az);
    out->ctr_x = (max_x + min_x) / 2;   /* Java int division (truncates) */
    out->ctr_z = (max_z + min_z) / 2;

    static const int ENTRY[3][2] = { {0, 7}, {7, 15}, {15, 8} };
    for (int i = 0; i < 3; i++) {
        int ox, oz;
        rot_xz(out->rot, ENTRY[i][0], ENTRY[i][1], &ox, &oz);
        out->entry_x[i] = px + ox;
        out->entry_z[i] = pz + oz;
    }

    /* Prefilter: piece_min <= max_y + 12 needs Hc <= T + 1 or some H(entry) <= T, and
     * H <= y requires the block at y to be non-solid. */
    if (max_y != INT_MAX) {
        int t = max_y + LYSH_OUTPOST_BBOX_INFLATE;
        int maybe = !solid_at(ctx, out->ctr_x, t + 1, out->ctr_z);
        for (int i = 0; i < 3 && !maybe; i++)
            maybe = !solid_at(ctx, out->entry_x[i], t, out->entry_z[i]);
        if (!maybe) { out->reject = LYSH_OP_REJECT_PREFILTER; return; }
    }

    out->h_center = lysh_phase2_height_pre_carver(&ctx->p2, out->ctr_x, out->ctr_z);
    out->biome = lysh_biome_at(&ctx->biome, &ctx->p2.terrain, out->ctr_x, out->h_center, out->ctr_z, NULL);
    if (!lysh_outpost_biome_allowed(out->biome, version)) { out->reject = LYSH_OP_REJECT_BIOME; return; }

    out->base_y = out->h_center - 1;
    out->piece_min_y = out->base_y;
    /* free space y-range: [max(Hc-80, -64), min(Hc+81, 320)); plate box [H, H+5] deflated 0.25 */
    int lo = out->h_center - 80 > -64 ? out->h_center - 80 : -64;
    int hi = out->h_center + 81 < 320 ? out->h_center + 81 : 320;
    for (int i = 0; i < 3; i++) {
        int h = lysh_phase2_height_pre_carver(&ctx->p2, out->entry_x[i], out->entry_z[i]);
        out->h_entry[i] = h;
        out->plate_ok[i] = h >= lo && h + 6 <= hi;
        if (out->plate_ok[i] && h < out->piece_min_y) out->piece_min_y = h;
    }
    out->bbox_min_y = out->piece_min_y - LYSH_OUTPOST_BBOX_INFLATE;
    out->reject = (max_y != INT_MAX && out->bbox_min_y > max_y) ? LYSH_OP_REJECT_Y : LYSH_OP_OK;
}

/* ---------------- scan session ---------------- */

#include <stdlib.h>
#include "search.h"

struct lysh_outpost_session {
    int version, threads;
    lysh_search_ctx *ctx;            /* one per thread */
};

typedef struct {
    lysh_outpost_session *s;
    int tid;
    int rx0, rx1, rz0, rz1, max_y;
    lysh_outpost_scan_out *out;      /* shared: only rows_done is written concurrently */
    lysh_outpost_result *hits;       /* private */
    int n, cap;
    long long counts[LYSH_OP_REJECT_N];
} op_worker;

static void op_worker_run(void *arg) {
    op_worker *w = (op_worker *)arg;
    lysh_search_ctx *ctx = &w->s->ctx[w->tid];
    for (int rz = w->rz0 + w->tid; rz < w->rz1; rz += w->s->threads) {
        for (int rx = w->rx0; rx < w->rx1; rx++) {
            lysh_outpost_result r;
            lysh_outpost_eval(ctx, w->s->version, rx, rz, w->max_y, &r);
            w->counts[r.reject]++;
            if (r.reject != LYSH_OP_OK) continue;
            if (w->n == w->cap) {
                int cap = w->cap ? w->cap * 2 : 64;
                lysh_outpost_result *p = (lysh_outpost_result *)realloc(w->hits, (size_t)cap * sizeof(*p));
                if (!p) continue;    /* out of memory: drop the hit, keep counting */
                w->hits = p;
                w->cap = cap;
            }
            w->hits[w->n++] = r;
        }
#if defined(__GNUC__)
        __atomic_add_fetch(&w->out->rows_done, 1, __ATOMIC_RELAXED);
#else
        w->out->rows_done++;   /* ponytail: racy progress counter on non-gcc builds */
#endif
    }
}

lysh_outpost_session *lysh_outpost_session_open(uint64_t seed, int version, int threads) {
    if (version < 0 || version >= LYSH_OP_V_N) return NULL;
    if (threads <= 0) threads = lysh_cpu_count();
    lysh_outpost_session *s = (lysh_outpost_session *)calloc(1, sizeof(*s));
    if (!s) return NULL;
    s->ctx = (lysh_search_ctx *)calloc((size_t)threads, sizeof(lysh_search_ctx));
    if (!s->ctx) { free(s); return NULL; }
    s->version = version;
    s->threads = threads;
    for (int i = 0; i < threads; i++) lysh_outpost_ctx_init(&s->ctx[i], seed, version);
    return s;
}

void lysh_outpost_session_free(lysh_outpost_session *s) {
    if (!s) return;
    for (int i = 0; i < s->threads; i++) lysh_search_ctx_free(&s->ctx[i]);
    free(s->ctx);
    free(s);
}

static int cmp_hit(const void *a, const void *b) {
    const lysh_outpost_result *x = (const lysh_outpost_result *)a, *y = (const lysh_outpost_result *)b;
    if (x->bbox_min_y != y->bbox_min_y) return x->bbox_min_y < y->bbox_min_y ? -1 : 1;
    if (x->cz != y->cz) return x->cz < y->cz ? -1 : 1;
    return (x->cx > y->cx) - (x->cx < y->cx);
}

void lysh_outpost_session_band(lysh_outpost_session *s, int rx0, int rx1, int rz0, int rz1,
                               int max_y, lysh_outpost_scan_out *out) {
    out->hits_written = 0;
    memset(out->counts, 0, sizeof(out->counts));
    out->scanned = (long long)(rx1 - rx0) * (rz1 - rz0);
    if (rx1 <= rx0 || rz1 <= rz0) return;

    int n = s->threads;
    op_worker *ws = (op_worker *)calloc((size_t)n, sizeof(op_worker));
    lysh_thread *ts = (lysh_thread *)calloc((size_t)n, sizeof(lysh_thread));
    if (!ws || !ts) { free(ws); free(ts); return; }
    for (int t = 0; t < n; t++) {
        ws[t] = (op_worker){ .s = s, .tid = t, .rx0 = rx0, .rx1 = rx1, .rz0 = rz0, .rz1 = rz1,
                             .max_y = max_y, .out = out };
    }
    if (n == 1) {
        op_worker_run(&ws[0]);
    } else {
        for (int t = 0; t < n; t++)
            if (lysh_thread_start(&ts[t], op_worker_run, &ws[t]) != 0) op_worker_run(&ws[t]);
        for (int t = 0; t < n; t++) if (ts[t].handle) lysh_thread_join(&ts[t]);
    }

    /* merge, lowest first, so a too-small buffer keeps the best hits */
    int total = 0;
    for (int t = 0; t < n; t++) total += ws[t].n;
    lysh_outpost_result *all = total ? (lysh_outpost_result *)malloc((size_t)total * sizeof(*all)) : NULL;
    int k = 0;
    for (int t = 0; t < n; t++) {
        for (int c = 0; c < LYSH_OP_REJECT_N; c++) out->counts[c] += ws[t].counts[c];
        if (all) { memcpy(all + k, ws[t].hits, (size_t)ws[t].n * sizeof(*all)); k += ws[t].n; }
        free(ws[t].hits);
    }
    if (all) {
        qsort(all, (size_t)total, sizeof(*all), cmp_hit);
        int m = out->hits ? (total < out->hits_cap ? total : out->hits_cap) : 0;
        if (m) memcpy(out->hits, all, (size_t)m * sizeof(*all));
        out->hits_written = m;
        free(all);
    }
    free(ws);
    free(ts);
}
