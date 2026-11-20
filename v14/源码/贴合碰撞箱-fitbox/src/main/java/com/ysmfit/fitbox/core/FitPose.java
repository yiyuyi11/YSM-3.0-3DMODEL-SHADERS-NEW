package com.ysmfit.fitbox.core;

/**
 * The body states that get their own hit shapes (and, unless they borrow another state's box, their own box), with the
 * YSM clip that poses the model in each: the model's own clip of that name, else YSM's builtin default clip it borrows
 * by name; the first name the model or the defaults have wins. Clip names follow YSM's animation states
 * (AnimationRegister): crawling on land and Battlefield Movement's prone stance play {@code climbing}, its transitions
 * and slides the {@code bfm:*} clips the humanoid importer generates, and TACZ's held-gun stances layer the
 * {@code tac:<stance>:<gun type>} clips over the body.
 */
public enum FitPose {
    STANDING(false, "idle"),
    /** Vanilla's PlayerRenderer#getRenderOffset lowers a crouching player's model by 1/8 block; YSM draws there too. */
    CROUCHING(false, -0.125f, "sneaking", "sneak"),
    SWIMMING(true, "swim"),
    FALL_FLYING(true, "elytra_fly", "fly"),
    SLEEPING(true, "sleep"),
    SPIN_ATTACK(true, "riptide", "swim"),
    SITTING(false, "sit", "ride"),
    RIDING(false, "ride", "sit"),
    RIDING_SMALL(false, "ride_pig", "ride"),
    BOAT(false, "boat", "sit"),
    /** Crawling on land (vanilla's crawl pose out of water) and Battlefield Movement's prone stance. */
    CRAWLING(true, "climbing", "climb", "swim"),
    /** Battlefield Movement going prone / getting up: the transition clip half way through. */
    PRONE_ENTER(0.5f, true, "bfm:prone_enter", "climbing"),
    PRONE_EXIT(0.5f, true, "bfm:prone_exit", "climbing"),
    /** Battlefield Movement slides: reclined (Battlefield V / CoD power slide) and crouched (Battlefield 1). */
    SLIDE(true, "bfm:slide", "climbing"),
    SLIDE_CROUCH(true, "bfm:slide_crouch", "sneaking"),
    /** Battlefield Movement dive: its held last frame. */
    DIVE(1f, true, "bfm:dive", "swim"),
    // TACZ stances: shapes only, the box stays the base state's.
    GUN_HOLD_RIFLE(STANDING, "tac:hold:rifle"),
    GUN_HOLD_PISTOL(STANDING, "tac:hold:pistol"),
    GUN_AIM_RIFLE(STANDING, "tac:aim:rifle"),
    GUN_AIM_PISTOL(STANDING, "tac:aim:pistol"),
    GUN_PRONE_RIFLE(CRAWLING, "tac:climbing:rifle"),
    GUN_PRONE_PISTOL(CRAWLING, "tac:climbing:pistol");

    /**
     * Lying (and sliding) poses get a vanilla-style footprint: the body's cross-section, not its length (see Fitter),
     * and, except slides, the lying height limits.
     */
    public final boolean lying;
    /** Vertical render offset of the whole model in blocks (applied before the entity's rotation). */
    public final float renderOffsetY;
    /** Where in the clip to sample: 0 start, 0.5 half way, 1 last keyframe. */
    public final float sampleAt;
    /** For overlay states: the state that supplies the body clip and the box. Null otherwise. */
    public final FitPose base;
    public final String[] clips;

    FitPose(boolean lying, String... clips) {
        this(lying, 0f, 0f, null, clips);
    }

    FitPose(boolean lying, float renderOffsetY, String... clips) {
        this(lying, renderOffsetY, 0f, null, clips);
    }

    FitPose(float sampleAt, boolean lying, String... clips) {
        this(lying, 0f, sampleAt, null, clips);
    }

    FitPose(FitPose base, String overlay) {
        this(base.lying, base.renderOffsetY, base.sampleAt, base, new String[] {overlay});
    }

    FitPose(boolean lying, float renderOffsetY, float sampleAt, FitPose base, String[] clips) {
        this.lying = lying;
        this.renderOffsetY = renderOffsetY;
        this.sampleAt = sampleAt;
        this.base = base;
        this.clips = clips;
    }

    /**
     * Height limits that apply: lying poses use the lying limits (under a block, so 1-block gaps stay passable);
     * slides are low but not lying (from the lying minimum up to the upright maximum); everything else upright.
     */
    public boolean lowHeight() {
        return this == SLIDE || this == SLIDE_CROUCH;
    }

    /** The state whose box this state uses. */
    public FitPose box() {
        return base != null ? base : this;
    }
}
