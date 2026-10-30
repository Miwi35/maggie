<?php

declare(strict_types=1);

namespace PsychedLabs\Testing\Tests\Mercure;

use PHPUnit\Framework\AssertionFailedError;
use PHPUnit\Framework\TestCase;
use PsychedLabs\Testing\Mercure\InMemoryMercureHub;
use PsychedLabs\Testing\Mercure\MercureAssertionTrait;
use Symfony\Component\Mercure\Update;

final class MercureAssertionTraitTest extends TestCase
{
    use MercureAssertionTrait;

    private InMemoryMercureHub $hub;

    protected function setUp(): void
    {
        $this->hub = new InMemoryMercureHub();
    }

    protected function getMercureHub(): InMemoryMercureHub
    {
        return $this->hub;
    }

    public function testItFindsAnUpdateByTopicSubstring(): void
    {
        $this->hub->publish(new Update('/users/1/api/stores/42', '{"name":"Lidl"}'));

        $this->assertMercureUpdatePublished('/api/stores');
        $this->assertMercureUpdateCount(1);
        $this->assertMercurePublishedOnce('/api/stores/42');
        $this->assertNothingPublishedOn('/api/stores/43');
        self::assertSame([['name' => 'Lidl']], $this->mercurePayloadsOn('/api/stores/42'));
    }

    public function testItFailsWhenNothingWasPublished(): void
    {
        $this->expectException(AssertionFailedError::class);

        $this->assertMercureUpdatePublished();
    }

    public function testItRecognisesADelete(): void
    {
        $this->hub->publish(new Update('/api/stores/42', '{"deleted":true}'));

        $this->assertMercureDeletePublished('/api/stores/42');
    }

    public function testItFailsOnADoublePublication(): void
    {
        $this->hub->publish(new Update('/api/stores/42', '{}'));
        $this->hub->publish(new Update('/api/stores/42', '{}'));

        $this->expectException(AssertionFailedError::class);

        $this->assertMercurePublishedOnce('/api/stores/42');
    }

    public function testResetClearsTheHub(): void
    {
        $this->hub->publish(new Update('/api/stores/42', '{}'));

        $this->resetMercure();

        $this->assertMercureUpdateCount(0);
    }
}
