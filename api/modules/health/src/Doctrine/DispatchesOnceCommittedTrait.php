<?php

declare(strict_types=1);

namespace Maggie\Health\Doctrine;

/**
 * For a listener whose effects flush again and must run after every other
 * postFlush (MAG-777): its handlers' flush would make another listener forget,
 * in its preFlush, what that one has not sent yet — and the other listener's
 * flush, made first, would make this one forget its own.
 *
 * So what onFlush notes waits in `$pending`, which a flush that failed leaves
 * there and the next preFlush forgets; {@see AgendaFlushCommitted} moves it to
 * `$ready` as soon as the flush is committed, out of reach of any nested
 * preFlush; the listener's own postFlush, last of all, sends what is ready.
 *
 * @template T of object
 */
trait DispatchesOnceCommittedTrait
{
    /** @var list<T> noted during a flush not committed yet */
    private array $pending = [];

    /** @var list<T> from committed flushes, waiting for the last postFlush */
    private array $ready = [];

    private bool $suspended = false;

    /** Fixtures store history as it is: what they flush has no effect here. */
    public function withoutEffects(\Closure $work): mixed
    {
        $this->suspended = true;
        try {
            return $work();
        } finally {
            $this->suspended = false;
        }
    }

    /** A flush that failed after onFlush never reached postFlush: what it noted must not outlive it. */
    public function preFlush(): void
    {
        $this->pending = [];
    }

    /** The flush is committed: what it noted will be sent, whatever flushes before the last postFlush. */
    public function committed(): void
    {
        array_push($this->ready, ...$this->pending);
        $this->pending = [];
    }

    /**
     * Forgets what is being sent before sending it: a handler flushes again.
     *
     * @return list<T>
     */
    private function takeReady(): array
    {
        $ready = $this->ready;
        $this->ready = [];

        return $ready;
    }
}
