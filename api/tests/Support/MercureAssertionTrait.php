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

    /**
     * The updates published on one item, whichever user's stream it went to:
     * `/api/stores/{id}` matches `/users/{userId}/api/stores/{id}`.
     *
     * @return list<array<string, mixed>> the decoded payloads, in publication order
     */
    protected function mercurePayloadsOn(string $itemTopic): array
    {
        $payloads = [];
        foreach ($this->getMercureHub()->getUpdates() as $update) {
            foreach ($update->getTopics() as $topic) {
                if (str_ends_with($topic, $itemTopic)) {
                    $payloads[] = json_decode($update->getData(), true, 512, JSON_THROW_ON_ERROR);
                }
            }
        }

        return $payloads;
    }

    /** Exactly one update on the item: two means a screen receives the same change twice. */
    protected function assertMercurePublishedOnce(string $itemTopic): void
    {
        self::assertCount(1, $this->mercurePayloadsOn($itemTopic), sprintf(
            'Expected exactly one Mercure update on %s. Published topics: %s',
            $itemTopic,
            $this->publishedTopics(),
        ));
    }

    protected function assertMercureDeletePublished(string $itemTopic): void
    {
        $payloads = $this->mercurePayloadsOn($itemTopic);
        self::assertCount(1, $payloads, sprintf(
            'Expected exactly one Mercure delete on %s. Published topics: %s',
            $itemTopic,
            $this->publishedTopics(),
        ));
        self::assertTrue($payloads[0]['deleted'] ?? false, 'The update on '.$itemTopic.' is not a delete.');
    }

    protected function assertNothingPublishedOn(string $itemTopic): void
    {
        self::assertSame([], $this->mercurePayloadsOn($itemTopic), 'Nothing should have been published on '.$itemTopic);
    }

    private function publishedTopics(): string
    {
        $topics = [];
        foreach ($this->getMercureHub()->getUpdates() as $update) {
            array_push($topics, ...$update->getTopics());
        }

        return implode(', ', $topics);
    }
}
