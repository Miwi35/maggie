<?php

namespace App\Tests\Support;

use Symfony\Component\Mercure\HubInterface;
use Symfony\Component\Mercure\Jwt\TokenFactoryInterface;
use Symfony\Component\Mercure\Update;
use Symfony\Component\Uid\Ulid;

class InMemoryMercureHub implements HubInterface
{
    /** @var Update[] */
    private array $updates = [];

    public function publish(Update $update): string
    {
        $this->updates[] = $update;

        return 'urn:uuid:' . new Ulid();
    }

    public function getPublicUrl(): string
    {
        return 'https://mercure.test/.well-known/mercure';
    }

    public function getFactory(): ?TokenFactoryInterface
    {
        return null;
    }

    /** @return Update[] */
    public function getUpdates(): array
    {
        return $this->updates;
    }

    public function reset(): void
    {
        $this->updates = [];
    }
}
