<?php

declare(strict_types=1);

namespace App\Tests\Support;

use Maggie\Core\Entity\User;
use Maggie\Core\Home\HomeCardProviderInterface;
use Maggie\Core\Home\HomeDay;
use Symfony\Component\Clock\ClockInterface;

/** Registered in the test kernel only: echoes who asked and for which date. */
final class FakeHomeCardProvider implements HomeCardProviderInterface
{
    public function __construct(private readonly ClockInterface $clock)
    {
    }

    public function module(): string
    {
        return 'tasks';
    }

    public function state(User $user, HomeDay $day): array
    {
        return ['userId' => (string) $user->getId(), 'date' => $day->date($this->clock)->format('Y-m-d')];
    }
}
