<?php

declare(strict_types=1);

namespace Maggie\Core\Home;

/**
 * A home card that refreshes by itself: {@see HomeCardPublisher} publishes its
 * topic, for the row's owner, each time a message changes a row of one of these
 * classes (or of a subclass). A provider implements it on top of
 * {@see HomeCardProviderInterface}.
 */
interface HomeCardWatcherInterface
{
    /**
     * The entities the card reads: a row inserted, updated or deleted in one of them moves the card.
     *
     * @return list<class-string>
     */
    public function watchedEntities(): array;
}
