<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Mcp\Tool\GetEventsTool;
use Maggie\Core\Mcp\MissingMcpUserException;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class GetEventsToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('GetEventsToolTest.yaml');
    }

    /** @return array<string, mixed> */
    private function read(?string $from = null, ?string $to = null, ?string $query = null, ?array $agendas = null): array
    {
        $tool = self::getContainer()->get(GetEventsTool::class);

        return json_decode($tool($from, $to, $query, $agendas), true, 512, JSON_THROW_ON_ERROR);
    }

    private function day(string $modifier): string
    {
        return (new \DateTimeImmutable($modifier))->format('Y-m-d');
    }

    /**
     * @param array<string, mixed> $data
     *
     * @return list<string>
     */
    private function summaries(array $data): array
    {
        return array_column($data['events'], 'summary');
    }

    public function testWithoutUserReturnsError(): void
    {
        $data = $this->read();

        self::assertSame(MissingMcpUserException::MESSAGE, $data['error']);
        self::assertArrayNotHasKey('events', $data);
    }

    public function testADayReadsThatDayOnly(): void
    {
        $this->loginFixtureUser();
        $tomorrow = $this->day('tomorrow');

        $data = $this->read($tomorrow, $tomorrow);

        self::assertSame($tomorrow, $data['from']);
        self::assertSame($tomorrow, $data['to']);
        self::assertSame(['Tomorrow meeting'], $this->summaries($data));
        self::assertSame(1, $data['count']);
        $event = $data['events'][0];
        foreach (['id', 'summary', 'description', 'location', 'allDay', 'startAt', 'endAt', 'status', 'agenda', 'recurring'] as $key) {
            self::assertArrayHasKey($key, $event);
        }
        self::assertSame('Test', $event['agenda']);
        self::assertSame('confirmed', $event['status']);
        self::assertFalse($event['recurring']);
    }

    public function testFromAloneReadsSevenDays(): void
    {
        $this->loginFixtureUser();
        $today = $this->day('today');

        $data = $this->read($today);

        self::assertSame($today, $data['from']);
        self::assertSame($this->day('+6 days'), $data['to']);
        self::assertSame(['Tomorrow meeting'], $this->summaries($data));

        $later = $this->read($this->day('+9 days'));
        self::assertContains('Nine days away', $this->summaries($later));
    }

    public function testWithoutFromReadsFromNow(): void
    {
        $this->loginFixtureUser();

        $data = $this->read();

        self::assertSame($this->day('today'), $data['from']);
        self::assertSame($this->day('+6 days'), $data['to']);
        self::assertSame(['Tomorrow meeting'], $this->summaries($data));
        self::assertNotContains('Cancelled', $this->summaries($data));
    }

    public function testQuerySearchesAYearAndReturnsASeriesOnce(): void
    {
        $this->loginFixtureUser();

        self::assertNotContains('Piano', $this->summaries($this->read()));

        $data = $this->read(null, null, 'piano');

        self::assertSame($this->day('+365 days'), $data['to']);
        self::assertSame(['Piano'], $this->summaries($data));
        self::assertTrue($data['events'][0]['recurring']);
        self::assertSame('FREQ=WEEKLY', $data['events'][0]['rrule']);
    }

    public function testRangeOver31DaysWithoutQueryIsRefused(): void
    {
        $this->loginFixtureUser();
        $today = $this->day('today');

        $data = $this->read($today, $this->day('+31 days'));

        self::assertStringContainsString('at most 31 days', $data['error']);
        self::assertArrayNotHasKey('events', $data);
        self::assertArrayNotHasKey('error', $this->read($today, $this->day('+30 days')));
        self::assertArrayNotHasKey('error', $this->read($today, $this->day('+365 days'), 'piano'));
        self::assertStringContainsString('at most 366 days', $this->read($today, $this->day('+366 days'), 'piano')['error']);
    }

    public function testToBeforeFromIsRefused(): void
    {
        $this->loginFixtureUser();

        $data = $this->read($this->day('+3 days'), $this->day('+1 days'));

        self::assertStringContainsString('is before from', $data['error']);
    }

    public function testDatesThatAreNotDatesAreRefused(): void
    {
        $this->loginFixtureUser();

        self::assertStringContainsString('YYYY-MM-DD', $this->read('lundi')['error']);
        self::assertStringContainsString('YYYY-MM-DD', $this->read($this->day('today'), 'demain')['error']);
    }

    public function testAgendasFilterUnknownNameListsAgendas(): void
    {
        $this->loginFixtureUser();

        $data = $this->read(null, null, null, ['Nope']);

        self::assertArrayNotHasKey('events', $data);
        self::assertStringContainsString('Nope', $data['error']);
        self::assertStringContainsString('Test', $data['error']);
        self::assertStringNotContainsString('Neighbour agenda', $data['error']);
    }

    public function testAnotherUsersEventsNeverComeBack(): void
    {
        $this->loginFixtureUser();

        self::assertNotContains('Neighbour tomorrow', $this->summaries($this->read()));
        self::assertNotContains('Neighbour tomorrow', $this->summaries($this->read(null, null, 'neighbour')));
    }
}
