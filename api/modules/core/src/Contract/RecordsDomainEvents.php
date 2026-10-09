<?php

declare(strict_types=1);

namespace Maggie\Core\Contract;

interface RecordsDomainEvents
{
    /**
     * Hands over the events recorded since the last call and forgets them.
     *
     * @return list<object>
     */
    public function releaseDomainEvents(): array;
}
