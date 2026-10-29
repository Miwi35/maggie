<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Entity\TaskReminder;
use Maggie\Calendar\Mcp\Tool\AnswerTaskReminderTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Uid\Ulid;

/** « C'est fait » said to a reminder (MAG-683): the tool answers it as the notification's button does. */
final class AnswerTaskReminderToolTest extends KernelTestCase
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
        $this->loadFixtures('AnswerTaskReminderToolTest.yaml');
        $this->loginFixtureUser();
    }

    public function testDoneTicksTheTaskAndRecordsTheAnswer(): void
    {
        $reminder = $this->reminder('garage', TaskReminder::KIND_DUE);
        $this->resetMercure();

        $data = $this->call((string) $reminder->getId(), 'done');

        self::assertTrue($data['success']);
        $this->em()->clear();
        self::assertSame('done', $this->em()->find(TaskReminder::class, $reminder->getId())?->getAction());
        self::assertNotNull($this->em()->find(Task::class, $this->getFixture('garage')->getId())?->getCompletedAt());
        $this->assertMercureUpdatePublished('/api/tasks/');
        $this->assertElasticsearchIndexDispatched(Task::class);
    }

    public function testAnUnknownActionIsRefusedAndNothingIsAnswered(): void
    {
        $reminder = $this->reminder('garage', TaskReminder::KIND_DUE);

        $data = $this->call((string) $reminder->getId(), 'later');

        self::assertStringContainsString('action must be one of', $data['error']);
        $this->em()->clear();
        self::assertNull($this->em()->find(TaskReminder::class, $reminder->getId())?->getAction());
    }

    public function testAReminderIsAnsweredOnce(): void
    {
        $reminder = $this->reminder('garage', TaskReminder::KIND_DUE);
        $this->call((string) $reminder->getId(), 'tomorrow');

        $data = $this->call((string) $reminder->getId(), 'done');

        self::assertArrayHasKey('error', $data);
        $this->em()->clear();
        self::assertSame('tomorrow', $this->em()->find(TaskReminder::class, $reminder->getId())?->getAction());
    }

    public function testAnActionTheKindDoesNotTakeIsRefused(): void
    {
        $reminder = $this->reminder('garage', TaskReminder::KIND_DUE);

        $data = $this->call((string) $reminder->getId(), 'tonight');

        self::assertArrayHasKey('error', $data);
        $this->em()->clear();
        self::assertNull($this->em()->find(TaskReminder::class, $reminder->getId())?->getAction());
    }

    public function testAnotherUsersReminderOrAMadeUpIdIsNotFound(): void
    {
        $other = $this->reminder('other_task', TaskReminder::KIND_DUE, 'other_user');

        self::assertSame('Reminder not found.', $this->call((string) $other->getId(), 'done')['error']);
        self::assertSame('Reminder not found.', $this->call((string) new Ulid(), 'done')['error']);
        self::assertSame('Reminder not found.', $this->call('not-an-id', 'done')['error']);
        $this->em()->clear();
        self::assertNull($this->em()->find(Task::class, $this->getFixture('other_task')->getId())?->getCompletedAt());
    }

    public function testWithoutAUserNothingIsAnswered(): void
    {
        $reminder = $this->reminder('garage', TaskReminder::KIND_DUE);
        self::getContainer()->get('security.token_storage')->setToken(null);

        self::assertArrayHasKey('error', $this->call((string) $reminder->getId(), 'done'));
        $this->em()->clear();
        self::assertNull($this->em()->find(TaskReminder::class, $reminder->getId())?->getAction());
    }

    private function reminder(string $taskRef, string $kind, string $userRef = 'test_user'): TaskReminder
    {
        $reminder = (new TaskReminder())
            ->setUser($this->getFixture($userRef))
            ->setKind($kind)
            ->setSubjectId((string) $this->getFixture($taskRef)->getId())
            ->setScheduledFor(new \DateTimeImmutable('now'))
            ->setSentAt(new \DateTimeImmutable('now'));
        $this->em()->persist($reminder);
        $this->em()->flush();

        return $reminder;
    }

    /** @return array<string, mixed> */
    private function call(string $reminderId, string $action): array
    {
        $tool = self::getContainer()->get(AnswerTaskReminderTool::class);

        return json_decode($tool($reminderId, $action), true, 512, JSON_THROW_ON_ERROR);
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}
