<?php

namespace App\Tests\Support;

trait MercureAssertionTrait
{
    protected function getMercureHub(): InMemoryMercureHub
    {
        return self::getContainer()->get(InMemoryMercureHub::class);
    }

    protected function resetMercure(): void
    {
        $this->getMercureHub()->reset();
    }

    protected function assertMercureUpdatePublished(?string $topicSubstring = null): void
    {
        $updates = $this->getMercureHub()->getUpdates();
        self::assertNotEmpty($updates, 'Expected at least one Mercure update to be published.');

        if (null !== $topicSubstring) {
            $found = false;
            foreach ($updates as $update) {
                foreach ($update->getTopics() as $topic) {
                    if (str_contains($topic, $topicSubstring)) {
                        $found = true;
                        break 2;
                    }
                }
            }
            self::assertTrue($found, sprintf(
                'No Mercure update with topic containing "%s" found. Topics: %s',
                $topicSubstring,
                implode(', ', array_merge(...array_map(fn ($u) => $u->getTopics(), $updates))),
            ));
        }
    }

    protected function assertMercureUpdateCount(int $expected): void
    {
        self::assertCount($expected, $this->getMercureHub()->getUpdates());
    }
}
