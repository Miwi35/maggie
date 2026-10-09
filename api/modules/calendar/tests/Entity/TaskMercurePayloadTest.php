<?php

namespace Maggie\Calendar\Tests\Entity;

use Maggie\Calendar\Entity\Task;
use PHPUnit\Framework\TestCase;

class TaskMercurePayloadTest extends TestCase
{
    public function testACompletedAtChangeIsPublishedAsIsDoneAlone(): void
    {
        $task = new Task();
        $task->setTitle('Complete me');
        $task->setCompletedAt(new \DateTimeImmutable());

        $payload = $task->toMercurePayload(['completedAt']);

        self::assertSame(['isDone' => true], $payload);
    }
}
