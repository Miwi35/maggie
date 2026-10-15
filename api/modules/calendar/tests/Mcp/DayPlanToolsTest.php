<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Entity\DayPlan;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Mcp\Tool\CreateTaskTool;
use Maggie\Calendar\Mcp\Tool\GetDayPlanTool;
use Maggie\Calendar\Mcp\Tool\SetDayPlanTool;
use Maggie\Calendar\Mcp\Tool\UpdateTaskTool;
use Maggie\Core\Mcp\MissingMcpUserException;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** Maggie's tools for the day (MAG-497): get_day_plan, set_day_plan, and the estimate and first step of a task. */
class DayPlanToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock('2026-10-14 12:00:00 UTC'));
        $this->loadFixtures('DayPlanToolsTest.yaml');
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

    /** @return array<string, mixed> */
    private static function decode(string $json): array
    {
        return json_decode($json, true, 512, JSON_THROW_ON_ERROR);
    }

    /** @param list<string> $refs @return array<string, mixed> */
    private function set(array $refs, ?string $date = null, bool $confirm = true): array
    {
        return self::decode((self::getContainer()->get(SetDayPlanTool::class))(array_map($this->id(...), $refs), $date, $confirm));
    }

    /** @return array<string, mixed> */
    private function get(?string $date = null): array
    {
        return self::decode((self::getContainer()->get(GetDayPlanTool::class))($date));
    }

    private function stored(string $date): ?DayPlan
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(DayPlan::class)->findOneBy(['user' => $this->getFixture('test_user')->getId(), 'date' => new \DateTimeImmutable($date)]);
    }

    public function testWithoutAUserBothToolsRefuse(): void
    {
        self::getContainer()->get('security.token_storage')->setToken(null);

        self::assertSame(MissingMcpUserException::MESSAGE, $this->get()['error']);
        self::assertSame(MissingMcpUserException::MESSAGE, self::decode((self::getContainer()->get(SetDayPlanTool::class))([$this->id('t1')]))['error']);
        self::assertNull($this->stored('2026-10-14'));
    }

    public function testGetReadsTodayInTheUsersTimezoneCreatingItEmpty(): void
    {
        $day = $this->get();

        self::assertSame('2026-10-14', $day['date']);
        self::assertSame([], $day['slots']);
        self::assertCount(7, $day['reserve']);
        $stored = $this->stored('2026-10-14');
        self::assertNotNull($stored);
        $this->assertMercureUpdatePublished('/api/day_plans/'.$stored->getId());
    }

    public function testSetWritesTheSlotsInOrderAndPublishes(): void
    {
        $data = $this->set(['t3', 't1']);

        self::assertTrue($data['success']);
        self::assertSame(['Arroser les plantes', 'Rappeler le garage'], array_map(static fn (array $slot): string => $slot['task']['title'], $data['day']['slots']));
        self::assertSame('Arroser les plantes', $data['day']['now']['task']['title']);
        $stored = $this->stored('2026-10-14');
        self::assertSame([$this->id('t3'), $this->id('t1')], $stored?->getTaskIds());
        self::assertNotNull($stored?->getConfirmedAt());
        $this->assertMercureUpdatePublished('/api/day_plans/'.$stored?->getId());
        $this->assertNoElasticsearchIndexDispatched(DayPlan::class);
    }

    public function testAProposalForTomorrowLeavesItUnsettled(): void
    {
        $data = $this->set(['t2'], '2026-10-15', false);

        self::assertSame('2026-10-15', $data['day']['date']);
        self::assertNull($this->stored('2026-10-15')?->getConfirmedAt());
    }

    public function testASixthTaskAnotherUsersTaskAndABadDateAreRefused(): void
    {
        $this->set(['t1']);

        self::assertStringContainsString('5 slots at most', $this->set(['t1', 't2', 't3', 't4', 't5', 't6'])['error']);
        self::assertStringContainsString('Task not found', $this->set(['t1', 'others'])['error']);
        self::assertStringContainsString('YYYY-MM-DD', $this->set(['t1'], 'demain')['error']);
        self::assertStringContainsString('YYYY-MM-DD', $this->get('demain')['error']);
        self::assertSame([$this->id('t1')], $this->stored('2026-10-14')?->getTaskIds());
    }

    public function testCreateAndUpdateTaskCarryTheEstimateAndTheFirstStep(): void
    {
        $created = self::decode((self::getContainer()->get(CreateTaskTool::class))('Appeler la mairie', estimatedMinutes: 15, firstStep: 'Chercher le numéro'));

        self::assertSame(15, $created['task']['estimatedMinutes']);
        self::assertSame('Chercher le numéro', $created['task']['firstStep']);
        self::assertNull($created['task']['postponedSince']);
        $this->assertElasticsearchIndexDispatchedFor(Task::class, $created['task']['id']);

        $updated = self::decode((self::getContainer()->get(UpdateTaskTool::class))($created['task']['id'], estimatedMinutes: 30));
        self::assertSame(30, $updated['task']['estimatedMinutes']);

        $cleared = self::decode((self::getContainer()->get(UpdateTaskTool::class))($created['task']['id'], clear: ['estimatedMinutes', 'firstStep']));
        self::assertNull($cleared['task']['estimatedMinutes']);
        self::assertNull($cleared['task']['firstStep']);

        $refused = self::decode((self::getContainer()->get(UpdateTaskTool::class))($created['task']['id'], estimatedMinutes: 0));
        self::assertStringContainsString('estimatedMinutes', $refused['error']);
    }

    public function testATaskDueMondayReadsPutOffSinceMonday(): void
    {
        $task = self::decode((self::getContainer()->get(UpdateTaskTool::class))($this->id('t1'), title: 'Rappeler le garage Martin'))['task'];

        self::assertSame('2026-10-12', $task['postponedSince']);
    }
}
