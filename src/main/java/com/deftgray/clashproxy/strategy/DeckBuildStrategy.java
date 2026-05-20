package com.deftgray.clashproxy.strategy;

/**
 * Strategy interface for deck generation.
 *
 * Implementations are tried in priority order by DeckService.
 * Each strategy decides whether it can handle the given context
 * and, if so, attempts to build a valid 8-card deck.
 *
 * Current implementations:
 *  - MetaLlmStrategy    (primary)  — meta intelligence + LLM decision + validation
 *  - LlmFallbackStrategy (fallback) — pure LLM generation (when no meta data)
 */
public interface DeckBuildStrategy {

    /**
     * Attempt to build a deck for the given context.
     *
     * @param context all player / request data needed to build a deck
     * @return a valid DeckBuildResult, or null if this strategy cannot produce one
     */
    DeckBuildResult build(DeckBuildContext context);

    /**
     * Quick pre-check: can this strategy potentially handle the context?
     * Returning true does not guarantee build() succeeds — it only avoids
     * unnecessary work when the strategy clearly cannot apply.
     */
    boolean canHandle(DeckBuildContext context);

    /** Human-readable name for logging. */
    String name();
}
