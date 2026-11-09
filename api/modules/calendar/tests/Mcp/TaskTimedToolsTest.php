<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Google\Service\Tasks\Task as GoogleTask;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Mcp\Tool\CreateTaskTool;
use Maggie\Calendar\Mcp\Tool\GetTasksTool;
use Maggie\Calendar\Mcp\Tool\UpdateTaskTool;
use Maggie\Calendar\Service\GoogleTasksApiClient;
use Maggie\Core\Mcp\MissingMcpUserException;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * « médite 20 minutes ce soir » through Maggie's tools (MAG-766): a task done
 * with a timer, for its estimated minutes.
 *
 * A `timed` that is not a boolean (« oui ») never reaches the tool: the MCP
 * SDK casts the argument strictly (true/false/1/0) and answers the JSON-RPC
 * call with invalid params before invoking it.
 */
final class TaskTimedToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('UpdateTaskToolTest.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    public function testTheToolsNeedAUser(): void
    {
        $created = $this->decode((self::getContainer()->get(CreateTaskTool::class))('Méditer', estimatedMinutes: 20, timed: true));
        $updated = $this->decode($this->update(timed: true));

        self::assertSame(MissingMcpUserException::MESSAGE, $created['error']);
        self::assertArrayHasKey('error', $updated);
        self::assertFalse($this->reload()->isTimed());
        $this->assertMercureUpdateCount(0);
    }

    public function testMeditate20MinutesCreatesATimedTask(): void
    {
        $this->loginFixtureUser();

        $data = $this->decode((self::getContainer()->get(CreateTaskTool::class))('Méditer', estimatedMinutes: 20, timed: true));

        self::assertTrue($data['success']);
        self::assertTrue($data['task']['timed']);
        self::assertSame(20, $data['task']['estimatedMinutes']);
        $task = $this->reload($data['task']['id']);
        self::assertTrue($task->isTimed());
        self::assertSame(20, $task->getEstimatedMinutes());
        $this->assertMercureUpdatePublished('/tasks/');
        $this->assertElasticsearchIndexDispatched(Task::class);
    }

    public function testAnEstimateAloneIsNotTimed(): void
    {
        $this->loginFixtureUser();

        $data = $this->decode((self::getContainer()->get(CreateTaskTool::class))('Appeler le garage', estimatedMinutes: 10));

        self::assertFalse($data['task']['timed']);
        self::assertFalse($this->reload($data['task']['id'])->isTimed());
    }

    public function testUpdateTurnsTheTimerOffAndLeavesItWhenNotGiven(): void
    {
        $this->loginFixtureUser();
        $this->decode($this->update(timed: true));
        self::assertTrue($this->reload()->isTimed());

        $this->decode((self::getContainer()->get(UpdateTaskTool::class))((string) $this->getFixture('task_done')->getId(), title: 'Renommée'));
        self::assertTrue($this->reload()->isTimed(), 'left out: unchanged');
        $this->resetMercure();
        $this->resetAsyncTransport();

        $data = $this->decode($this->update(timed: false));

        self::assertTrue($data['success']);
        self::assertFalse($data['task']['timed']);
        self::assertFalse($this->reload()->isTimed());
        $this->assertMercureUpdatePublished('/tasks/');
        $this->assertElasticsearchIndexDispatched(Task::class);
    }

    public function testGetTasksReadsTimed(): void
    {
        $this->loginFixtureUser();
        $this->decode($this->update(timed: true));

        $data = $this->decode((self::getContainer()->get(GetTasksTool::class))('all'));

        $timed = array_column($data['tasks'], 'timed', 'title');
        self::assertTrue($timed['Done task']);
        self::assertFalse($timed['Préparer le déménagement']);
    }

    /** Maggie's own field, like estimatedMinutes: the timer alone pushes nothing to Google. */
    public function testTheTimerAloneIsNotPushedToGoogle(): void
    {
        $this->loadFixtures(\dirname(__DIR__).'/Api/fixtures/TaskGoogleMoveApiTest.yaml');
        $this->loginFixtureUser('google_user');
        $google = $this->createMock(GoogleTasksApiClient::class);
        self::getContainer()->set(GoogleTasksApiClient::class, $google);
        $id = (string) $this->getFixture('moving')->getId();

        $google->expects(self::once())->method('patchTask')->willReturn(new GoogleTask(['id' => 'g-moving', 'title' => 'Renommée']));
        $this->decode((self::getContainer()->get(UpdateTaskTool::class))($id, timed: true));
        $this->decode((self::getContainer()->get(UpdateTaskTool::class))($id, title: 'Renommée'));

        self::assertTrue($this->reload($id)->isTimed());
    }

    private function update(?bool $timed = null): string
    {
        return (self::getContainer()->get(UpdateTaskTool::class))(
            (string) $this->getFixture('task_done')->getId(),
            timed: $timed,
        );
    }

    /** @return array<string, mixed> */
    private function decode(string $json): array
    {
        return json_decode($json, true, 512, JSON_THROW_ON_ERROR);
    }

    private function reload(?string $id = null): Task
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $task = $em->getRepository(Task::class)->find($id ?? $this->getFixture('task_done')->getId());
        self::assertInstanceOf(Task::class, $task);

        return $task;
    }
}
