package net.mynecraft.world.actor;

/**
 * Skills and abilities of one actor (player or inhabitant). Practice gives experience in a skill
 * (level = sqrt(xp / 5), up to 10) and trains its two abilities a little; a skill left unpractised
 * for more than two days slowly blunts (1 % of its experience a day). A master teaching makes
 * practice count double.
 */
public final class Skills {
    public static final int MAX_LEVEL = 10;
    private static final Skill[] S = Skill.values();
    private static final Ability[] A = Ability.values();

    public final double[] xp = new double[S.length];
    /** 0..1, start at 0.3. */
    public final double[] ability = new double[A.length];
    /** Day each skill was last practised (for the blunting, and the variety of activities). */
    public final double[] lastPractice = new double[S.length];
    /** Days until which practice of a skill counts double (a lesson from a master). */
    public final double[] taughtUntil = new double[S.length];
    /** The calendar day, updated by {@link #rust}. */
    public double today;

    public Skills() {
        java.util.Arrays.fill(ability, 0.3);
        java.util.Arrays.fill(lastPractice, -100);
    }

    public static int levelFor(double xp) {
        return Math.min(MAX_LEVEL, (int) Math.floor(Math.sqrt(Math.max(0, xp) / 5)));
    }

    public int level(Skill s) {
        return levelFor(xp[s.ordinal()]);
    }

    /** Practising a skill: experience (doubled after a lesson), and its abilities grow a little. */
    public void practice(Skill s, double amount) {
        int i = s.ordinal();
        if (today < taughtUntil[i]) amount *= 2;
        xp[i] += amount;
        lastPractice[i] = today;
        grow(s.primary, amount * 0.002);
        grow(s.secondary, amount * 0.001);
    }

    private void grow(Ability a, double d) {
        int i = a.ordinal();
        ability[i] = Math.min(1, ability[i] + d * (1 - ability[i]));
    }

    /** Advances the calendar: skills idle for more than two days lose 1 % of their experience per day. */
    public void rust(double day) {
        double dt = day - today;
        today = day;
        if (dt <= 0) return;
        for (int i = 0; i < S.length; i++)
            if (day - lastPractice[i] > 2) xp[i] *= Math.pow(0.99, dt);
    }

    /**
     * How well and fast a gesture of this skill goes: 1 for a novice of average abilities, up to
     * about 2 for a master; {@code condition} (0..1) is the body's state (tired, hungry, cold).
     */
    public double efficiency(Skill s, double condition) {
        return (1 + 0.06 * level(s)) * (0.8 + 0.5 * ability[s.primary.ordinal()] + 0.2 * ability[s.secondary.ordinal()])
            * (0.5 + 0.5 * condition);
    }

    public int level(Job j) {
        int l = MAX_LEVEL;
        for (Skill s : j.skills) l = Math.min(l, level(s));
        return l;
    }

    /** The trade one is known for: the best job (more skills breaks ties), or null for a novice. */
    public Job role() {
        Job best = null;
        int bestLevel = 0;
        for (Job j : Job.values()) {
            int l = level(j);
            if (l > bestLevel || (l == bestLevel && l > 0 && best != null && j.skills.length > best.skills.length)) {
                best = j;
                bestLevel = l;
            }
        }
        return best;
    }

    /** "compagnon charpentier", or "" for a novice. */
    public String roleTitle() {
        Job j = role();
        return j == null ? "" : Job.title(level(j)) + " " + j.displayName;
    }

    /** Distinct skills practised during the last day (variety of activities). */
    public int recentSkills() {
        int n = 0;
        for (double d : lastPractice) if (today - d < 1) n++;
        return n;
    }
}
