package org.ddlsmith;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Seeded pseudo-random source for DDLSmith.
 *
 * <p>Adapted from GDsmith/SQLancer's {@code Randomly}. Every random decision of the tool flows
 * through this class, so a run can be reproduced exactly from its seed. Values are cached and
 * re-used with some probability so that generated schemas contain repeated constants (e.g. the
 * same default value or the same CHECK bound appearing in several places), which is what makes
 * constraint interactions interesting.</p>
 */
public final class Randomly {

    public enum StringGenerationStrategy {
        ALPHANUMERIC, NUMERIC, ALPHANUMERIC_SPECIALCHAR, SOPHISTICATED;
    }

    private static StringGenerationStrategy stringGenerationStrategy = StringGenerationStrategy.ALPHANUMERIC;
    private static int maxStringLength = 10;
    private static boolean useCaching = true;
    private static int cacheSize = 100;

    private static final ThreadLocal<Random> THREAD_RANDOM = ThreadLocal.withInitial(Random::new);
    private static final ThreadLocal<Long> THREAD_SEED = ThreadLocal.withInitial(() -> 0L);

    /**
     * The constant caches are per thread, not global.
     *
     * <p>They used to be plain static lists, which made them shared mutable state between the
     * worker threads of a run. Every worker calls {@link #setSeed(long)} before generating its own
     * database, and that clears the cache — so one thread clearing the list while another was
     * reading it produced {@code IndexOutOfBoundsException: Index 2 out of bounds for length 0} and
     * killed that thread's database. Two of thirty-two databases died this way on H2 with eight
     * threads. The same sharing also leaked one thread's constants into another's input, so the
     * same seed stopped reproducing the same database as soon as {@code --threads} was greater
     * than one.</p>
     */
    private static final ThreadLocal<List<Long>> CACHED_LONGS = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<List<String>> CACHED_STRINGS = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<List<Double>> CACHED_DOUBLES = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<List<byte[]>> CACHED_BYTES = ThreadLocal.withInitial(ArrayList::new);

    private static Supplier<String> provider;

    /**
     * Random decisions are drawn from a thread-local generator that is seeded through
     * {@link #setSeed(long)}, so this constructor only exists for the GDsmith-style
     * {@code new Randomly()} idiom used by the executor plumbing.
     */
    public Randomly() {
    }

    public Randomly(long seed) {
        setSeed(seed);
    }

    /* ------------------------------------------------------------------ seeding */

    public static void initialize(MainOptions options) {
        if (options.getRandomSeed() == -1) {
            setSeed(System.currentTimeMillis());
        } else {
            setSeed(options.getRandomSeed());
        }
        if (options.getMaxStringLength() != -1) {
            maxStringLength = options.getMaxStringLength();
        }
        useCaching = options.useConstantCaching();
        if (options.getStringGenerationStrategy() != null) {
            stringGenerationStrategy = options.getStringGenerationStrategy();
        }
    }

    public static void setSeed(long seed) {
        THREAD_RANDOM.set(new Random(seed));
        THREAD_SEED.set(seed);
        clearCache();
    }

    public static long getSeed() {
        return THREAD_SEED.get();
    }

    /** Clears the constant cache of the calling thread only, so a seed starts from a clean state. */
    public static void clearCache() {
        CACHED_LONGS.get().clear();
        CACHED_STRINGS.get().clear();
        CACHED_DOUBLES.get().clear();
        CACHED_BYTES.get().clear();
    }

    /* ------------------------------------------------------------------ caching */

    private static void addToCache(long val) {
        List<Long> cache = CACHED_LONGS.get();
        if (useCaching && cache.size() < cacheSize && !cache.contains(val)) {
            cache.add(val);
        }
    }

    private static void addToCache(double val) {
        List<Double> cache = CACHED_DOUBLES.get();
        if (useCaching && cache.size() < cacheSize && !cache.contains(val)) {
            cache.add(val);
        }
    }

    private static void addToCache(String val) {
        List<String> cache = CACHED_STRINGS.get();
        if (useCaching && cache.size() < cacheSize && !cache.contains(val)) {
            cache.add(val);
        }
    }

    private static Long getFromLongCache() {
        List<Long> cache = CACHED_LONGS.get();
        if (!useCaching || cache.isEmpty()) {
            return null;
        }
        return fromList(cache);
    }

    private static Double getFromDoubleCache() {
        if (!useCaching) {
            return null;
        }
        List<Long> longs = CACHED_LONGS.get();
        List<Double> doubles = CACHED_DOUBLES.get();
        if (getBoolean() && !longs.isEmpty()) {
            return (double) fromList(longs);
        } else if (!doubles.isEmpty()) {
            return fromList(doubles);
        }
        return null;
    }

    private static String getFromStringCache() {
        if (!useCaching) {
            return null;
        }
        List<Long> longs = CACHED_LONGS.get();
        List<Double> doubles = CACHED_DOUBLES.get();
        List<String> strings = CACHED_STRINGS.get();
        if (getBoolean() && !longs.isEmpty()) {
            return String.valueOf(fromList(longs));
        } else if (getBoolean() && !doubles.isEmpty()) {
            return String.valueOf(fromList(doubles));
        } else if (!strings.isEmpty()) {
            String randomString = fromList(strings);
            if (getBoolean()) {
                return randomString;
            }
            return stringGenerationStrategy == StringGenerationStrategy.SOPHISTICATED
                    ? transformCachedString(randomString)
                    : randomString;
        }
        return null;
    }

    private static boolean cacheProbability() {
        return useCaching && getNextLong(0, 3) == 1;
    }

    private static String transformCachedString(String cached) {
        int length = cached.length();
        if (length == 0) {
            return cached;
        }
        switch (getNextInt(0, 3)) {
        case 0:
            return cached.toUpperCase();
        case 1:
            return cached + cached;
        default:
            return cached.substring(0, Math.max(1, length - 1));
        }
    }

    /* ------------------------------------------------------------------ primitives */

    private static long getNextLong() {
        return THREAD_RANDOM.get().nextLong();
    }

    private static long getNextLong(long bound) {
        return THREAD_RANDOM.get().nextLong(bound);
    }

    private static int getNextInt(int bound) {
        return THREAD_RANDOM.get().nextInt(bound);
    }

    /**
     * Uniform draw from {@code [origin, bound)} computed in {@code long} arithmetic so that the full
     * {@code int} range is supported (the naive {@code bound - origin} overflows for
     * {@code [Integer.MIN_VALUE, Integer.MAX_VALUE)}).
     */
    private static int getNextInt(int origin, int bound) {
        if (origin >= bound) {
            return origin;
        }
        long range = (long) bound - origin;
        return (int) (origin + Math.floorMod(THREAD_RANDOM.get().nextLong(), range));
    }

    private static long getNextLong(long origin, long bound) {
        if (origin >= bound) {
            return origin;
        }
        return origin + Math.floorMod(THREAD_RANDOM.get().nextLong(), bound - origin);
    }

    public static int getInteger() {
        Long fromCache = getFromLongCache();
        if (fromCache != null && getBoolean()) {
            return fromCache.intValue();
        }
        // draw over the whole 64 bit range and narrow: this covers the full int range uniformly
        int val = (int) getNextLong();
        addToCache(val);
        return val;
    }

    /** @return a value in [0, bound) */
    public static int getInteger(int bound) {
        if (bound <= 0) {
            return 0;
        }
        Long fromCache = getFromLongCache();
        if (fromCache != null && getBoolean()) {
            return Math.floorMod(fromCache, bound);
        }
        int val = getNextInt(bound);
        addToCache(val);
        return val;
    }

    /** @return a value in [min, max) */
    public static int getInteger(int min, int max) {
        if (min >= max) {
            return min;
        }
        Long fromCache = getFromLongCache();
        if (fromCache != null && getBoolean()) {
            return min + (int) Math.floorMod(fromCache, (long) (max - min));
        }
        int val = getNextInt(min, max);
        addToCache(val);
        return val;
    }

    public static long getLong() {
        Long fromCache = getFromLongCache();
        if (fromCache != null && getBoolean()) {
            return fromCache;
        }
        long val = getNextLong();
        addToCache(val);
        return val;
    }

    public static long getLong(long min, long max) {
        if (min >= max) {
            return min;
        }
        Long fromCache = getFromLongCache();
        if (fromCache != null && getBoolean()) {
            return min + Math.floorMod(fromCache, max - min);
        }
        long val = getNextLong(min, max);
        addToCache(val);
        return val;
    }

    /**
     * A uniform draw from {@code [0, 1)}.
     *
     * <p>This is the primitive that {@link #getBoolean(double)} and {@link #getDouble(double, double)}
     * are built on, so it deliberately does <em>not</em> go through the constant cache: re-using a
     * previously drawn constant here would destroy the uniformity that probability decisions rely
     * on.</p>
     */
    public static double getDouble() {
        double val = THREAD_RANDOM.get().nextDouble();
        addToCache(val);
        return val;
    }

    public static double getDouble(double min, double max) {
        if (min >= max) {
            return min;
        }
        return min + getDouble() * (max - min);
    }

    public static boolean getBoolean() {
        return getNextInt(0, 2) == 0;
    }

    /** @return true with probability {@code p} */
    public static boolean getBoolean(double p) {
        return getDouble() < p;
    }

    public static char getChar() {
        return (char) getNextInt('a', 'z' + 1);
    }

    public static char getUpperChar() {
        return (char) getNextInt('A', 'Z' + 1);
    }

    public static char getDigitChar() {
        return (char) getNextInt('0', '9' + 1);
    }

    /* ------------------------------------------------------------------ strings */

    public static String getString() {
        String fromCache = getFromStringCache();
        if (fromCache != null && getBoolean()) {
            return fromCache;
        }
        String val = generateString();
        addToCache(val);
        return val;
    }

    public static String getString(int maxLen) {
        if (!useCaching || maxLen <= 1) {
            StringBuilder sb = new StringBuilder();
            int len = getNextInt(1, Math.max(2, maxLen));
            for (int i = 0; i < len; i++) {
                sb.append(getChar());
            }
            return sb.toString();
        }
        String val;
        do {
            val = getString();
        } while (val.length() > maxLen);
        return val;
    }

    private static String generateString() {
        if (provider != null) {
            return provider.get();
        }
        int len = getNextInt(1, maxStringLength + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            switch (stringGenerationStrategy) {
            case NUMERIC:
                sb.append(getDigitChar());
                break;
            case ALPHANUMERIC_SPECIALCHAR:
                switch (getNextInt(0, 4)) {
                case 0:
                    sb.append(getChar());
                    break;
                case 1:
                    sb.append(getUpperChar());
                    break;
                case 2:
                    sb.append(getDigitChar());
                    break;
                default:
                    sb.append(fromOptions(' ', '\'', '"', '%', '_', '-', '.', ',', ';', '(', ')'));
                    break;
                }
                break;
            default:
                if (getBoolean()) {
                    sb.append(getChar());
                } else {
                    sb.append(getUpperChar());
                }
                break;
            }
        }
        return sb.toString();
    }

    /** @return either {@code null} or a random string; useful for nullable text columns */
    public static String getNullOrString() {
        return getBoolean() ? null : getString();
    }

    public static String getStringWithLength(int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(getChar());
        }
        return sb.toString();
    }

    /* ------------------------------------------------------------------ collections */

    public static <T> T fromList(List<T> list) {
        if (list.isEmpty()) {
            throw new IllegalArgumentException("cannot pick from an empty list");
        }
        return list.get(getNextInt(list.size()));
    }

    @SafeVarargs
    public static <T> T fromOptions(T... options) {
        if (options.length == 0) {
            throw new IllegalArgumentException("cannot pick from an empty option set");
        }
        return options[getNextInt(options.length)];
    }

    @SafeVarargs
    public static <T> List<T> nonEmptySubset(T... options) {
        int nr = 1 + getNextInt(options.length);
        return extractNrRandomColumns(new ArrayList<>(List.of(options)), nr);
    }

    public static <T> List<T> nonEmptySubset(List<T> columns) {
        int nr = 1 + getNextInt(columns.size());
        return nonEmptySubset(columns, nr);
    }

    public static <T> List<T> nonEmptySubset(List<T> columns, int nr) {
        if (nr > columns.size()) {
            throw new IllegalArgumentException(columns + " " + nr);
        }
        return extractNrRandomColumns(columns, nr);
    }

    public static <T> List<T> subset(List<T> columns, int nr) {
        if (nr > columns.size()) {
            throw new IllegalArgumentException(columns + " " + nr);
        }
        return extractNrRandomColumns(columns, nr);
    }

    /** @return up to {@code nr} distinct random elements of {@code columns} (order randomized) */
    public static <T> List<T> extractNrRandomColumns(List<T> columns, int nr) {
        if (nr > columns.size()) {
            throw new IllegalArgumentException(columns + " " + nr);
        }
        List<T> remaining = new ArrayList<>(columns);
        List<T> selected = new ArrayList<>();
        for (int i = 0; i < nr; i++) {
            int idx = getNextInt(remaining.size());
            selected.add(remaining.remove(idx));
        }
        return selected;
    }

    /** In-place Fisher-Yates shuffle using this generator (reproducible). */
    public static <T> void shuffle(List<T> list) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = getNextInt(i + 1);
            T tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }

    /** @return a subset of {@code n} booleans, each true with probability {@code p} */
    public static boolean[] getBooleanArray(int n, double p) {
        boolean[] result = new boolean[n];
        for (int i = 0; i < n; i++) {
            result[i] = getBoolean(p);
        }
        return result;
    }

    public static void setStringProvider(Supplier<String> provider) {
        Randomly.provider = provider;
    }
}
