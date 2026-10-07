<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Mcp\Tool\UpdateEventTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class UpdateEventToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('UpdateEventToolTest.yaml');
        $this->loginFixtureUser();
    }

    private function tool(): UpdateEventTool
    {
        return self::getContainer()->get(UpdateEventTool::class);
    }

    private function id(): string
    {
        return (string) $this->getFixture('event_full')->getId();
    }

    private function reload(): Event
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Event::class)->find($this->getFixture('event_full')->getId());
    }

    public function testClearEmptiesOptionalFields(): void
    {
        $data = json_decode(
            ($this->tool())($this->id(), clear: ['description', 'location', 'rrule', 'summary']),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        $event = $this->reload();
        self::assertNull($event->getDescription());
        self::assertNull($event->getLocation());
        self::assertNull($event->getRrule());
        self::assertSame('Weekly sync', $event->getSummary(), 'Required fields cannot be cleared');
        $this->assertMercureUpdatePublished('/events/');
        $this->assertElasticsearchIndexDispatched(Event::class);
    }

    public function testWithoutClearNullFieldsAreLeftUntouched(): void
    {
        $data = json_decode(($this->tool())($this->id(), title: 'Renamed'), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        $event = $this->reload();
        self::assertSame('Renamed', $event->getSummary());
        self::assertSame('Bring slides', $event->getDescription());
        self::assertSame('Room 4', $event->getLocation());
        self::assertSame('FREQ=WEEKLY', $event->getRrule());
    }

    public function testUnknownEventReturnsAnError(): void
    {
        $data = json_decode(($this->tool())('01ARZ3NDEKTSV4RRFFQ69G5FAV', clear: ['description']), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }

    /**
     * "Finalement, préviens-moi la veille" — the reminders are replaced, not added to.
     *
     * Replacing rather than merging is what the owner means when he names the
     * reminders he wants, and it is the only reading that can ever remove one.
     */
    public function testRemindersReplaceWhatTheEventHad(): void
    {
        $data = json_decode(($this->tool())($this->id(), reminders: [1440]), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertSame([1440], $data['event']['reminders']);
        self::assertSame(
            ['useDefault' => false, 'overrides' => [['method' => 'popup', 'minutes' => 1440]]],
            $this->reload()->getReminders(),
        );
    }

    /** "Enlève le rappel" — said as `clear`, or as a list that came back empty. */
    public function testRemindersAreClearedBothWays(): void
    {
        $data = json_decode(($this->tool())($this->id(), clear: ['reminders']), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertNull($this->reload()->getReminders());

        $this->loadFixtures('UpdateEventToolTest.yaml');
        $data = json_decode(($this->tool())($this->id(), reminders: []), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertSame([], $data['event']['reminders']);
        self::assertNull($this->reload()->getReminders());
    }

    public function testAReminderOfZeroMinutesIsRefusedAndChangesNothing(): void
    {
        $data = json_decode(($this->tool())($this->id(), reminders: [0]), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayNotHasKey('success', $data);
        self::assertStringContainsString('between 1 and', $data['error']);
        self::assertSame(
            ['useDefault' => false, 'overrides' => [['method' => 'popup', 'minutes' => 15]]],
            $this->reload()->getReminders(),
        );
    }
}
