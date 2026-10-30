<?php

namespace Maggie\Health\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Enum\WorkoutSource;
use Maggie\Health\Enum\WorkoutType;
use Maggie\Health\Mcp\Tool\ManageWorkoutsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** manage_workouts (MAG-705): every action, and requests that need a deduction refused. */
class WorkoutToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Api/fixtures/workout.yaml');
        $this->resetMercure();
    }

    /** @return array<string, mixed> */
    private function call(string $action, mixed ...$arguments): array
    {
        $tool = self::getContainer()->get(ManageWorkoutsTool::class);
        \assert($tool instanceof ManageWorkoutsTool);

        return json_decode($tool($action, ...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    private function id(string $fixture): string
    {
        return (string) $this->getFixture($fixture)->getId();
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em;
    }

    private function reload(string $fixture): ?Workout
    {
        return $this->em()->find(Workout::class, $this->id($fixture));
    }

    private function rows(): int
    {
        return $this->em()->getRepository(Workout::class)->count([]);
    }

    public function testWithoutAUserItAnswersAnError(): void
    {
        $data = $this->call('list');

        self::assertArrayHasKey('error', $data);
        self::assertArrayNotHasKey('workouts', $data);
    }

    public function testAnUnknownActionIsRefused(): void
    {
        $this->loginFixtureUser();

        self::assertStringContainsString('Unknown action', $this->call('archive')['error']);
    }

    public function testListGivesOnlyTheUsersWorkoutsMostRecentFirstWithLabels(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('list');

        self::assertSame(['Marche rapide', 'Course', 'Haut du corps'], array_column($data['workouts'], 'title'));
        $run = $data['workouts'][1];
        self::assertSame($this->id('run'), $run['id']);
        self::assertSame('Course à pied', $run['typeLabel']);
        self::assertSame('Bien', $run['feelingLabel']);
        self::assertSame('Montre', $run['sourceLabel']);
        self::assertSame(5400, $run['distanceMeters']);
        self::assertNull($data['workouts'][2]['feelingLabel']);
    }

    public function testListHonoursItsFilters(): void
    {
        $this->loginFixtureUser();

        self::assertSame(['Course'], array_column($this->call('list', type: 'run')['workouts'], 'title'));
        self::assertSame(['Marche rapide'], array_column($this->call('list', source: 'planned')['workouts'], 'title'));
        self::assertSame(['Marche rapide', 'Course'], array_column($this->call('list', after: '2026-10-19T00:00:00+02:00')['workouts'], 'title'));
        self::assertSame(['Haut du corps'], array_column($this->call('list', before: '2026-10-19T00:00:00+02:00')['workouts'], 'title'));
        self::assertCount(1, $this->call('list', limit: 1)['workouts']);
    }

    public function testAnInvalidFilterIsRefusedNamingTheField(): void
    {
        $this->loginFixtureUser();

        self::assertSame('type', $this->call('list', type: 'yoga')['field']);
        self::assertSame('after', $this->call('list', after: '2026-10-19')['field']);
    }

    public function testACreateMissingTheDurationNamesIt(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('create', type: 'run', title: 'Course', startedAt: '2026-10-22T07:10:00+02:00');

        self::assertSame('durationMinutes', $data['field']);
        self::assertStringContainsString('durationMinutes is required', $data['error']);
        self::assertSame(4, $this->rows());
        $this->assertMercureUpdateCount(0);
    }

    public function testACreateMissingTheTypeTheTitleOrTheStartNamesThem(): void
    {
        $this->loginFixtureUser();

        self::assertSame('type', $this->call('create', title: 'Course', startedAt: '2026-10-22T07:10:00+02:00', durationMinutes: 32)['field']);
        self::assertSame('title', $this->call('create', type: 'run', startedAt: '2026-10-22T07:10:00+02:00', durationMinutes: 32)['field']);
        self::assertSame('startedAt', $this->call('create', type: 'run', title: 'Course', durationMinutes: 32)['field']);
        self::assertSame(4, $this->rows());
    }

    /** « J'ai fait une course de 32 min, 5,4 km ce matin ». */
    public function testCreatePersistsPublishesAndDefaultsToManual(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('create', type: 'run', title: 'Course', startedAt: '2026-10-22T07:10:00+02:00', durationMinutes: 32, distanceMeters: 5400);

        self::assertTrue($data['success']);
        self::assertSame('manual', $data['workout']['source']);
        self::assertSame('Saisie', $data['workout']['sourceLabel']);

        $workout = $this->em()->getRepository(Workout::class)->findOneBy(['startedAt' => new \DateTimeImmutable('2026-10-22T07:10:00+02:00')]);
        self::assertInstanceOf(Workout::class, $workout);
        self::assertSame(WorkoutType::Run, $workout->getType());
        self::assertSame(32, $workout->getDurationMinutes());
        self::assertSame(5400, $workout->getDistanceMeters());
        self::assertSame(WorkoutSource::Manual, $workout->getSource());
        self::assertSame($this->id('test_user'), (string) $workout->getUser()->getId());
        $this->assertMercureUpdatePublished('/api/workouts/'.$workout->getId());
    }

    public function testACreateWithoutAnOffsetIsRefused(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('create', type: 'run', title: 'Course', startedAt: '2026-10-22', durationMinutes: 32);

        self::assertSame('startedAt', $data['field']);
        self::assertSame(4, $this->rows());
    }

    public function testAnUpdateWithNothingToChangeAnswersTheWorkout(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('update', workoutId: $this->id('run'));

        self::assertStringContainsString('Nothing to change', $data['error']);
        self::assertSame('Course', $data['current']['title']);
        $this->assertMercureUpdateCount(0);
    }

    public function testAnUpdateWithADateOnlyIsRefusedWithTheWorkoutAsItIs(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('update', workoutId: $this->id('run'), startedAt: '2026-10-20');

        self::assertSame('startedAt', $data['field']);
        self::assertEquals(new \DateTimeImmutable('2026-10-20T07:10:00+02:00'), new \DateTimeImmutable($data['current']['startedAt']));
        self::assertEquals(new \DateTimeImmutable('2026-10-20T07:10:00+02:00'), $this->reload('run')?->getStartedAt());
        $this->assertMercureUpdateCount(0);
    }

    public function testUpdateChangesOnlyWhatIsGiven(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('update', workoutId: $this->id('run'), durationMinutes: 40, feeling: 'hard');

        self::assertTrue($data['success']);
        $run = $this->reload('run');
        self::assertInstanceOf(Workout::class, $run);
        self::assertSame(40, $run->getDurationMinutes());
        self::assertSame('Course', $run->getTitle());
        self::assertSame(5400, $run->getDistanceMeters());
        self::assertSame('hard', $run->getFeeling()?->value);
        $this->assertMercureUpdatePublished('/api/workouts/'.$this->id('run'));
    }

    public function testUpdateAndDeleteNeedTheId(): void
    {
        $this->loginFixtureUser();

        self::assertSame('workoutId', $this->call('update', title: 'x')['field']);
        self::assertSame('workoutId', $this->call('delete')['field']);
    }

    public function testDeleteRemovesAndPublishes(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('delete', workoutId: $this->id('strength'));

        self::assertTrue($data['success']);
        self::assertNull($this->reload('strength'));
        $this->assertMercureDeletePublished('/api/workouts/'.$this->id('strength'));
    }

    public function testAnotherUsersWorkoutIsNotFound(): void
    {
        $this->loginFixtureUser();

        $update = $this->call('update', workoutId: $this->id('secret'), title: 'Volé');
        $delete = $this->call('delete', workoutId: $this->id('secret'));

        self::assertStringContainsString('Workout not found', $update['error']);
        self::assertStringContainsString('Workout not found', $delete['error']);
        self::assertSame('Séance secrète', $this->reload('secret')?->getTitle());
        $this->assertMercureUpdateCount(0);
    }
}
