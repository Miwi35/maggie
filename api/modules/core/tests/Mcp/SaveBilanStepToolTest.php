<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\ActivityStatus;
use Maggie\Core\Enum\Activity;
use Maggie\Core\Mcp\Tool\SaveBilanStepTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** `save_bilan_step` (MAG-749): the agent writes the step of the evening review at every passage. */
class SaveBilanStepToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Controller/fixtures/activity_status.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    /** @return array<string, mixed> */
    private function call(string $day, string $step, ?array $answers = null, ?array $instructions = null): array
    {
        $tool = self::getContainer()->get(SaveBilanStepTool::class);

        return json_decode($tool($day, $step, $answers, $instructions), true, 512, JSON_THROW_ON_ERROR);
    }

    private function stored(): ?ActivityStatus
    {
        $this->em()->clear();

        return $this->em()->getRepository(ActivityStatus::class)->findOneBy([
            'user' => $this->getFixture('test_user'),
            'activity' => Activity::Bilan,
        ]);
    }

    public function testWithoutAUserBoundReturnsAnError(): void
    {
        self::assertArrayHasKey('error', $this->call('2026-10-25', 'journee'));
        $this->assertMercureUpdateCount(0);
    }

    public function testAnUnknownStepIsRefusedAndNothingIsWritten(): void
    {
        $this->loginUser($this->getFixture('test_user'));

        self::assertArrayHasKey('error', $this->call('2026-10-25', 'dessert'));
        self::assertNull($this->stored());
        $this->assertMercureUpdateCount(0);
    }

    public function testABadDayIsRefused(): void
    {
        $this->loginUser($this->getFixture('test_user'));

        self::assertArrayHasKey('error', $this->call('demain', 'journee'));
        self::assertNull($this->stored());
    }

    public function testAFirstCallOpensTheBilanAtItsStep(): void
    {
        $this->loginUser($this->getFixture('test_user'));

        $result = $this->call('2026-10-25', 'journee');

        self::assertTrue($result['success']);
        self::assertSame('journee', $result['status']['state']['step']);
        $stored = $this->stored();
        self::assertSame('day', $stored->getOriginKind());
        self::assertSame('2026-10-25', $stored->getOriginId());
        self::assertSame('journee', json_decode($stored->getState(), true)['step']);
        $this->assertMercureUpdatePublished('/users/'.$this->getFixture('test_user')->getId().'/api/activity-status/bilan');
    }

    public function testMovingOnKeepsTheAnswersAndInstructionsOfTheSameDay(): void
    {
        $this->loginUser($this->getFixture('test_user'));
        $answers = ['tasks' => [['taskId' => 't1', 'choice' => 'tomorrow']]];
        $instructions = [['id' => 'i1', 'text' => 'Appeler le plombier', 'kind' => 'reminder']];
        $this->call('2026-10-25', 'journee', $answers, $instructions);

        $this->call('2026-10-25', 'finance');

        $state = json_decode($this->stored()->getState(), true);
        self::assertSame('finance', $state['step']);
        self::assertSame($answers, $state['answers']);
        self::assertSame($instructions, $state['instructions']);
    }

    public function testMovingOnWithoutAnyAnswerYetStillWrites(): void
    {
        $this->loginUser($this->getFixture('test_user'));
        $this->call('2026-10-25', 'journee');

        $result = $this->call('2026-10-25', 'finance');

        self::assertTrue($result['success'], json_encode($result));
        self::assertSame('finance', json_decode($this->stored()->getState(), true)['step']);
    }

    public function testEmptyAnswersSentByTheModelAreAnEmptyObject(): void
    {
        $this->loginUser($this->getFixture('test_user'));

        $result = $this->call('2026-10-25', 'finance', []);

        self::assertTrue($result['success'], json_encode($result));
        self::assertSame('finance', json_decode($this->stored()->getState(), true)['step']);
    }

    public function testAListOfTasksSentAsAnswersIsReadAsTheTaskAnswers(): void
    {
        $this->loginUser($this->getFixture('test_user'));
        $tasks = [['taskId' => 't1', 'choice' => 'done']];

        $result = $this->call('2026-10-25', 'finance', $tasks);

        self::assertTrue($result['success'], json_encode($result));
        self::assertSame(['tasks' => $tasks], json_decode($this->stored()->getState(), true)['answers']);
    }

    public function testANewDayStartsFromNothing(): void
    {
        $this->loginUser($this->getFixture('test_user'));
        $this->call('2026-10-25', 'consignes', ['mood' => 7], [['id' => 'i1', 'text' => 'x', 'kind' => 'task']]);

        $this->call('2026-10-26', 'journee');

        $stored = $this->stored();
        self::assertSame('2026-10-26', $stored->getOriginId());
        $state = json_decode($stored->getState(), true);
        self::assertSame([], $state['answers']);
        self::assertSame([], $state['instructions']);
    }
}
