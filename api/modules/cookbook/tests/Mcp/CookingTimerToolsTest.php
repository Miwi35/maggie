<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\CookingTimer;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Enum\CookingTimerStatus;
use Maggie\Cookbook\Mcp\Tool\ManageCookingTimersTool;
use Maggie\Cookbook\Message\FinishCookingTimerCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** `manage_cooking_timers` (MAG-582): « il reste combien pour les pâtes ? ». */
class CookingTimerToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    private MockClock $clock;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->clock = new MockClock('2026-10-25 19:00:00 UTC');
        Clock::set($this->clock);
        $this->loadFixtures('cooking_timer.yaml');
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    private function login(string $ref = 'test_user'): void
    {
        $this->loginFixtureUser($ref);
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @return array<string, mixed> */
    private function timers(mixed ...$args): array
    {
        return json_decode(self::getContainer()->get(ManageCookingTimersTool::class)(...$args), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function startPasta(): array
    {
        return $this->timers('start', label: 'Pâtes', recipeId: $this->recipeId('pasta'), stepPosition: 3, durationSeconds: 720);
    }

    private function recipeId(string $ref): string
    {
        $recipe = $this->getFixture($ref);
        self::assertInstanceOf(Recipe::class, $recipe);

        return (string) $recipe->getId();
    }

    private function stored(string $id): CookingTimer
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();
        $timer = $em->find(CookingTimer::class, $id);
        self::assertInstanceOf(CookingTimer::class, $timer);

        return $timer;
    }

    public function testEveryActionRequiresAUser(): void
    {
        self::assertArrayHasKey('error', $this->timers('list'));
        self::assertArrayHasKey('error', $this->startPasta());
        self::assertSame(0, self::getContainer()->get('doctrine.orm.entity_manager')->getRepository(CookingTimer::class)->count([]));
    }

    public function testStartStoresPublishesAndQueuesTheEnd(): void
    {
        $this->login();

        $data = $this->startPasta();

        self::assertTrue($data['success']);
        self::assertSame('running', $data['timer']['status']);
        self::assertSame(720, $data['timer']['remainingSeconds']);
        self::assertSame(CookingTimerStatus::Running, $this->stored($data['timer']['id'])->getStatus());
        $this->assertMercureUpdatePublished('/api/cooking/timers/'.$data['timer']['id']);
        // Not searched, so never indexed.
        $this->assertNoElasticsearchIndexDispatched(CookingTimer::class);
        $ends = array_filter($this->getAsyncTransport()->getSent(), static fn ($envelope) => $envelope->getMessage() instanceof FinishCookingTimerCommand);
        self::assertCount(1, $ends);
    }

    public function testListSaysHowLongIsLeftForEachTimer(): void
    {
        $this->login();
        $this->startPasta();
        $this->timers('start', label: 'Légumes', recipeId: $this->recipeId('pasta'), stepPosition: 4, durationSeconds: 480);
        $this->clock->sleep(300);

        $data = $this->timers('list');

        self::assertSame(['Pâtes', 'Légumes'], array_column($data['timers'], 'label'));
        self::assertSame([420, 180], array_column($data['timers'], 'remainingSeconds'));
    }

    public function testPauseAddResumeAndCancel(): void
    {
        $this->login();
        $id = $this->startPasta()['timer']['id'];
        $this->clock->sleep(120);

        $paused = $this->timers('pause', timerId: $id);
        self::assertSame('paused', $paused['timer']['status']);
        self::assertSame(600, $paused['timer']['remainingSeconds']);

        $added = $this->timers('add', timerId: $id, seconds: 60);
        self::assertSame(660, $added['timer']['remainingSeconds']);

        $resumed = $this->timers('resume', timerId: $id);
        self::assertSame('running', $resumed['timer']['status']);
        self::assertSame('2026-10-25T19:13:00+00:00', $resumed['timer']['endsAt']);

        $cancelled = $this->timers('cancel', timerId: $id);
        self::assertSame('cancelled', $cancelled['timer']['status']);
        self::assertSame(CookingTimerStatus::Cancelled, $this->stored($id)->getStatus());
        self::assertSame([], $this->timers('list')['timers']);
    }

    public function testAMissingFieldIsRefusedWithTheTimersAsTheyStand(): void
    {
        $this->login();
        $id = $this->startPasta()['timer']['id'];

        $noDuration = $this->timers('start', label: 'Eau', recipeId: $this->recipeId('pasta'), stepPosition: 1);
        self::assertStringContainsString('durationSeconds', $noDuration['error']);
        self::assertSame([$id], array_column($noDuration['timers'], 'id'));

        $noTimer = $this->timers('pause');
        self::assertStringContainsString('timerId', $noTimer['error']);
        self::assertSame([$id], array_column($noTimer['timers'], 'id'));

        $noSeconds = $this->timers('add', timerId: $id);
        self::assertStringContainsString('seconds', $noSeconds['error']);

        self::assertArrayHasKey('error', $this->timers('start', label: 'Eau', recipeId: $this->recipeId('pasta'), stepPosition: 1, durationSeconds: 0));
        self::assertArrayHasKey('error', $this->timers('resume', timerId: $id));
        self::assertArrayHasKey('error', $this->timers('ring'));
        self::assertSame(CookingTimerStatus::Running, $this->stored($id)->getStatus());
        self::assertSame(0, $this->stored($id)->getRevision());
    }

    public function testAnotherUsersTimerAndRecipeAreOutOfReach(): void
    {
        $this->login('other_user');
        $theirs = $this->timers('start', label: 'Soupe', recipeId: $this->recipeId('other_recipe'), stepPosition: 1, durationSeconds: 60)['timer']['id'];
        $this->login();

        self::assertStringContainsString('not found', $this->timers('cancel', timerId: $theirs)['error']);
        self::assertStringContainsString('not found', $this->timers('start', label: 'Soupe', recipeId: $this->recipeId('other_recipe'), stepPosition: 1, durationSeconds: 60)['error']);
        self::assertSame([], $this->timers('list')['timers']);
        self::assertSame(CookingTimerStatus::Running, $this->stored($theirs)->getStatus());
    }
}
