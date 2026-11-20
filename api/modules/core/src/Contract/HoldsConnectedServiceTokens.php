<?php

declare(strict_types=1);

namespace Maggie\Core\Contract;

/**
 * An entity that stores a connected-service token (MAG-889). Its mapped
 * column holds the sealed value; the plain one stays in memory, sealed at
 * flush and opened at load by ConnectedServiceTokensListener — callers keep
 * using the plain getters and setters.
 */
interface HoldsConnectedServiceTokens
{
    /** The additional data a token is sealed under: `{table}:{record id}:{owner id}:{usage}`. */
    public function tokenContext(string $usage): string;

    /**
     * The tokens set since the last load or seal, by usage: they stay pending until storeSealedToken().
     *
     * @return array<string, ?string>
     */
    public function tokensToSeal(): array;

    /** Stores a sealed value in the mapped column of the usage, and marks the usage as up to date. */
    public function storeSealedToken(string $usage, ?string $sealed): void;

    /**
     * What the mapped columns hold, by usage.
     *
     * @return array<string, ?string>
     */
    public function sealedTokens(): array;

    /**
     * The opened values after a load; an unreadable usage is never handed out.
     *
     * @param array<string, ?string> $plain
     * @param list<string>           $unreadable
     */
    public function openTokens(array $plain, array $unreadable): void;
}
