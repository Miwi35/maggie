<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Routine;
use Maggie\Calendar\Entity\RoutineCheck;
use Maggie\Calendar\Mcp\Tool\CheckRoutineTool;
use Maggie\Calendar\Mcp\Tool\ListRoutinesTool;
use Maggie\Calendar\Mcp\Tool\SaveRoutineTool;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Discovery\SchemaValidator;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** Maggie's tools for the routines (MAG-513): list_routines, save_routine, check_routine — the app's rules, through the same bus. */
class RoutineToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const string TODAY = '2026-10-20';

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock(self::TODAY.' 10:00:00 UTC'));
        $this->loadFixtures('RoutineToolsTest.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loginFixtureUser();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    /** @return array<string, mixed> */
    private static function decode(string $json): array
    {
        return json_decode($json, true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function list(?string $date = null): array
    {
        return self::decode((self::getContainer()->get(ListRoutinesTool::class))($date));
    }

    /** @param array<string, mixed> $arguments @return array<string, mixed> */
    private function save(array $arguments): array
    {
        return self::decode((self::getContainer()->get(SaveRoutineTool::class))(...$arguments));
    }

    /** @return array<string, mixed> */
    private function check(string $id, ?string $date = null, bool $done = true): array
    {
        return self::decode((self::getContainer()->get(CheckRoutineTool::class))($id, $date, $done));
    }

    private function stored(string $id): ?Routine
    {
        $this->em()->clear();

        return $this->em()->find(Routine::class, $id);
    }

    /** @return list<string> the ticked dates, in order */
    private function ticks(string $routineId): array
    {
        $this->em()->clear();
        $dates = array_map(
            static fn (RoutineCheck $check): string => $check->getDate()->format('Y-m-d'),
            $this->em()->getRepository(RoutineCheck::class)->findBy(['routine' => $routineId]),
        );
        sort($dates);

        return $dates;
    }

    private function countRoutines(): int
    {
        $this->em()->clear();

        return $this->em()->getRepository(Routine::class)->count([]);
    }

    /** @return list<string> */
    private static function keys(mixed $value): array
    {
        if (!\is_array($value)) {
            return [];
        }
        $keys = [];
        foreach ($value as $key => $item) {
            $keys = [...$keys, ...(\is_string($key) ? [$key] : []), ...self::keys($item)];
        }

        return $keys;
    }

    public function testWithoutAUserTheThreeToolsRefuse(): void
    {
        self::getContainer()->get('security.token_storage')->setToken(null);
        $before = $this->countRoutines();

        self::assertSame(MissingMcpUserException::MESSAGE, $this->list()['error']);
        self::assertSame(MissingMcpUserException::MESSAGE, $this->list(self::TODAY)['error']);
        self::assertSame(MissingMcpUserException::MESSAGE, $this->check($this->id('water'))['error']);
        self::assertSame(MissingMcpUserException::MESSAGE, $this->save(['title' => 'Marcher', 'anchor' => 'wake', 'recurrence' => ['kind' => 'daily']])['error']);
        self::assertSame($before, $this->countRoutines());
        self::assertSame([], $this->ticks($this->id('water')));
    }

    public function testListReadsEveryRoutineOfTheUserWithItsTotalsAndNoStreak(): void
    {
        $this->check($this->id('water'), '2026-10-18');
        $this->check($this->id('water'), '2026-10-19');
        $this->check($this->id('water'), '2026-09-30');

        $data = $this->list();
        $byTitle = array_column($data['routines'], null, 'title');

        // Another user's « Méditer » is not there; the others are in the order of the day.
        self::assertSame(['Aérer la chambre', "Grand verre d'eau", 'Sortir les poubelles'], array_keys($byTitle));
        self::assertSame(['kind' => 'weekly', 'weekday' => 2], $byTitle['Sortir les poubelles']['recurrence']);
        self::assertSame('afterDinner', $byTitle['Sortir les poubelles']['anchor']);
        self::assertEquals(['monthCount' => 2, 'totalCount' => 3, 'checkedToday' => false, 'dueToday' => true], array_intersect_key($byTitle["Grand verre d'eau"], array_flip(['monthCount', 'totalCount', 'checkedToday', 'dueToday'])));

        // What was done, never a streak: no key says one, however deep.
        foreach (self::keys($data) as $key) {
            self::assertDoesNotMatchRegularExpression('/streak|series|serie|recent|missed/i', $key);
        }
    }

    public function testListOfADayGroupsTheDueRoutinesByMomentWithTheirTick(): void
    {
        $this->check($this->id('water'));

        $day = $this->list(self::TODAY);

        self::assertSame(self::TODAY, $day['date']);
        self::assertSame(['wake', 'afterDinner'], array_column($day['moments'], 'anchor'));
        $wake = $day['moments'][0]['routines'];
        // « Aérer la chambre » is monthly on the 31st: not due the 20th.
        self::assertSame(["Grand verre d'eau"], array_column($wake, 'title'));
        self::assertTrue($wake[0]['checked']);
        self::assertSame(['Sortir les poubelles'], array_column($day['moments'][1]['routines'], 'title'));
        self::assertFalse($day['moments'][1]['routines'][0]['checked']);
        self::assertNotContains('recentDays', array_keys($wake[0]));

        // Wednesday: the weekly one is not due.
        self::assertSame(['wake'], array_column($this->list('2026-10-21')['moments'], 'anchor'));
        self::assertStringContainsString('YYYY-MM-DD', $this->list('demain')['error']);
    }

    public function testCheckingTodayStoresTheTickPublishesItAndUncheckingRemovesIt(): void
    {
        $water = $this->id('water');

        $checked = $this->check($water);

        self::assertTrue($checked['success']);
        self::assertSame(self::TODAY, $checked['date']);
        self::assertTrue($checked['routine']['checkedToday']);
        self::assertSame(1, $checked['routine']['monthCount']);
        self::assertSame([self::TODAY], $this->ticks($water));
        $topic = '/api/routine_checks/'.$this->em()->getRepository(RoutineCheck::class)->findOneBy(['routine' => $water])?->getId();
        $this->assertMercurePublishedOnce($topic);
        $this->assertNoElasticsearchIndexDispatched(RoutineCheck::class);

        // Ticked twice: still one, nothing more announced.
        $this->resetMercure();
        $this->check($water);
        self::assertSame([self::TODAY], $this->ticks($water));
        $this->assertMercureUpdateCount(0);

        $unchecked = $this->check($water, null, false);

        self::assertFalse($unchecked['routine']['checkedToday']);
        self::assertSame([], $this->ticks($water));
        $this->assertMercureDeletePublished($topic);

        // Taking back a tick that is not there changes nothing.
        $this->resetMercure();
        self::assertTrue($this->check($water, null, false)['success']);
        $this->assertMercureUpdateCount(0);
    }

    public function testCheckingAnotherDayTicksThatDayOnly(): void
    {
        $trash = $this->id('trash');

        self::assertTrue($this->check($trash, '2026-10-18')['success']);

        self::assertSame(['2026-10-18'], $this->ticks($trash));
    }

    public function testADayToComeOrBeforeTheRoutineOrNotADayIsRefused(): void
    {
        $water = $this->id('water');
        $this->resetMercure();

        self::assertStringContainsString('still to come', $this->check($water, '2026-10-21')['error']);
        self::assertStringContainsString('before the routine was created', $this->check($water, '2026-09-14')['error']);
        self::assertStringContainsString('YYYY-MM-DD', $this->check($water, 'hier')['error']);

        self::assertSame([], $this->ticks($water));
        $this->assertMercureUpdateCount(0);
    }

    public function testCreatingWritesTheRoutineAsTheAppDoes(): void
    {
        $data = $this->save(['title' => 'Sortir le compost', 'anchor' => 'afterDinner', 'recurrence' => ['kind' => 'weekly', 'weekday' => 4]]);

        self::assertTrue($data['success']);
        $stored = $this->stored($data['routine']['id']);
        self::assertSame('Sortir le compost', $stored?->getTitle());
        self::assertSame('afterDinner', $stored->getAnchor());
        self::assertNull($stored->getAnchorTime());
        self::assertSame(['kind' => 'weekly', 'weekday' => 4], $stored->getRecurrence());
        self::assertTrue($stored->isActive());
        self::assertFalse($stored->isRemind());
        self::assertSame($this->getFixture('test_user')->getId()->toRfc4122(), $stored->getUser()->getId()->toRfc4122());
        $this->assertMercurePublishedOnce('/api/routines/'.$data['routine']['id']);
        $this->assertNoElasticsearchIndexDispatched(Routine::class);
    }

    public function testAnHourAnchorKeepsItsHour(): void
    {
        $data = $this->save(['title' => 'Appeler maman', 'anchor' => 'time', 'anchorTime' => '18:30', 'recurrence' => ['kind' => 'daily'], 'remind' => true]);

        self::assertTrue($data['success']);
        $stored = $this->stored($data['routine']['id']);
        self::assertSame(['time', '18:30', true], [$stored?->getAnchor(), $stored->getAnchorTime(), $stored->isRemind()]);
    }

    public function testCreatingWithoutAWholeShapeIsRefusedAndNothingIsDeduced(): void
    {
        $before = $this->countRoutines();

        $noRecurrence = $this->save(['title' => 'Marcher', 'anchor' => 'wake']);
        self::assertSame(['recurrence'], $noRecurrence['missing']);
        self::assertStringContainsString('recurrence', $noRecurrence['error']);
        self::assertSame(['title' => 'Marcher', 'anchor' => 'wake'], $noRecurrence['given']);

        self::assertSame(['title', 'anchor', 'recurrence'], $this->save(['active' => false])['missing']);
        self::assertSame(['anchor'], $this->save(['title' => 'Marcher', 'recurrence' => ['kind' => 'daily']])['missing']);

        // Incoherent: an hour without a time, a weekly rhythm without its day, a day with a moment.
        self::assertStringContainsString('anchorTime', $this->save(['title' => 'Marcher', 'anchor' => 'time', 'recurrence' => ['kind' => 'daily']])['error']);
        self::assertStringContainsString('recurrence.weekday', $this->save(['title' => 'Marcher', 'anchor' => 'wake', 'recurrence' => ['kind' => 'weekly']])['error']);
        self::assertStringContainsString('anchorTime', $this->save(['title' => 'Marcher', 'anchor' => 'wake', 'anchorTime' => '07:00', 'recurrence' => ['kind' => 'daily']])['error']);
        self::assertStringContainsString('anchor', $this->save(['title' => 'Marcher', 'anchor' => 'noon', 'recurrence' => ['kind' => 'daily']])['error']);

        self::assertSame($before, $this->countRoutines());
        $this->assertMercureUpdateCount(0);
    }

    public function testChangingOnlyActiveChangesNothingElse(): void
    {
        $trash = $this->id('trash');

        $data = $this->save(['id' => $trash, 'active' => false]);

        self::assertTrue($data['success']);
        self::assertFalse($data['routine']['active']);
        $stored = $this->stored($trash);
        self::assertFalse($stored?->isActive());
        self::assertSame(['Sortir les poubelles', 'afterDinner', ['kind' => 'weekly', 'weekday' => 2], false], [$stored->getTitle(), $stored->getAnchor(), $stored->getRecurrence(), $stored->isRemind()]);
        $this->assertMercureUpdatePublished('/api/routines/'.$trash);
    }

    public function testMovingToAMomentDropsTheHourAndAnHourNeedsItsTime(): void
    {
        $water = $this->id('water');
        self::assertTrue($this->save(['id' => $water, 'anchor' => 'time', 'anchorTime' => '07:15'])['success']);
        self::assertSame('07:15', $this->stored($water)?->getAnchorTime());

        self::assertTrue($this->save(['id' => $water, 'anchor' => 'afterLunch'])['success']);
        $stored = $this->stored($water);
        self::assertSame(['afterLunch', null], [$stored?->getAnchor(), $stored->getAnchorTime()]);

        // An hour with no time is refused: none is made up.
        self::assertStringContainsString('anchorTime', $this->save(['id' => $water, 'anchor' => 'time'])['error']);
        self::assertSame('afterLunch', $this->stored($water)?->getAnchor());
    }

    public function testARefusedChangeAnswersWithTheRoutineAsItStandsAndChangesNothing(): void
    {
        $trash = $this->id('trash');
        $this->resetMercure();

        $refused = $this->save(['id' => $trash, 'title' => 'Poubelles', 'recurrence' => ['kind' => 'weekly']]);

        self::assertStringContainsString('recurrence.weekday', $refused['error']);
        self::assertSame(['Sortir les poubelles', ['kind' => 'weekly', 'weekday' => 2]], [$refused['routine']['title'], $refused['routine']['recurrence']]);
        $stored = $this->stored($trash);
        self::assertSame(['Sortir les poubelles', ['kind' => 'weekly', 'weekday' => 2]], [$stored?->getTitle(), $stored->getRecurrence()]);
        $this->assertMercureUpdateCount(0);

        $nothing = $this->save(['id' => $trash]);
        self::assertStringContainsString('Nothing to change', $nothing['error']);
        self::assertSame($trash, $nothing['routine']['id']);
    }

    public function testAnotherUsersRoutineIsNotFoundByTheThreeTools(): void
    {
        $others = $this->id('others');
        $this->resetMercure();

        self::assertStringContainsString('Routine not found', $this->check($others)['error']);
        self::assertStringContainsString('Routine not found', $this->save(['id' => $others, 'active' => false])['error']);
        self::assertStringContainsString('Routine not found', $this->save(['id' => 'nope', 'active' => false])['error']);

        self::assertSame([], $this->ticks($others));
        self::assertTrue($this->stored($others)?->isActive());
        self::assertNotContains('Méditer', array_column($this->list()['routines'], 'title'));
        $this->assertMercureUpdateCount(0);
    }

    public function testTheSaveSchemaTakesTheRecurrenceAsAnObject(): void
    {
        $tools = json_decode((string) file_get_contents(\dirname(__DIR__, 4).'/contract/mcp-tools.json'), true, 512, JSON_THROW_ON_ERROR);
        $schema = $tools['save_routine']['inputSchema'];
        $validator = new SchemaValidator();

        // What the model sends, as the server reads it before the tool runs.
        $sent = json_decode('{"title":"Sortir les poubelles","anchor":"afterDinner","recurrence":{"kind":"weekly","weekday":2}}', true, 512, JSON_THROW_ON_ERROR);
        self::assertSame([], $validator->validateAgainstJsonSchema($sent, $schema));

        $sent['recurrence']['weekday'] = 9;
        self::assertNotSame([], $validator->validateAgainstJsonSchema($sent, $schema));
        self::assertNotSame([], $validator->validateAgainstJsonSchema(['title' => 'x', 'anchor' => 'noon', 'recurrence' => ['kind' => 'daily']], $schema));
    }
}
