<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Service;

use Google\Model as GoogleModel;
use Google\Service\Tasks\Task as GoogleTask;
use Google\Service\Tasks\TaskLinks;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Service\GoogleTaskMapper;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\TestCase;

/** Google Tasks' fields, both ways (MAG-404). */
class GoogleTaskMapperTest extends TestCase
{
    public function testFromGoogleTakesTheNotesTheDayThePositionAndTheLinks(): void
    {
        $googleTask = new GoogleTask();
        $googleTask->setId('g-1');
        $googleTask->setTitle('Préparer le déménagement');
        $googleTask->setNotes('Les cartons sont à la cave');
        $googleTask->setDue('2026-03-20T00:00:00.000Z');
        $googleTask->setStatus('needsAction');
        $googleTask->setPosition('00000000000000000003');
        $link = new TaskLinks();
        $link->setType('email');
        $link->setDescription('Le mail');
        $link->setLink('https://mail.google.com/mail/#all/abc');
        $googleTask->setLinks([$link]);

        $task = (new GoogleTaskMapper())->fromGoogle($googleTask, new User());

        self::assertSame('Les cartons sont à la cave', $task->getDescription());
        self::assertSame('2026-03-20', $task->getDueDate()?->format('Y-m-d'));
        self::assertSame('00000000000000000003', $task->getPosition());
        self::assertSame([['type' => 'email', 'description' => 'Le mail', 'link' => 'https://mail.google.com/mail/#all/abc']], $task->getLinks());
    }

    public function testATaskWithoutLinksOrDueDateHasNone(): void
    {
        $googleTask = new GoogleTask();
        $googleTask->setId('g-1');
        $googleTask->setTitle('Rien');
        $googleTask->setStatus('needsAction');

        $existing = (new Task())->setDueDate(new \DateTimeImmutable('2026-03-20'))->setLinks([['type' => 'email', 'description' => '', 'link' => 'x']]);
        $task = (new GoogleTaskMapper())->fromGoogle($googleTask, new User(), $existing);

        self::assertNull($task->getDueDate());
        self::assertSame([], $task->getLinks());
    }

    /** The day goes as Google's midnight UTC, whatever zone the object holds. */
    public function testToGoogleSendsTheDayAtMidnightUtc(): void
    {
        $task = (new Task())->setTitle('T')->setDueDate(new \DateTimeImmutable('2026-03-20', new \DateTimeZone('Pacific/Kiritimati')));

        self::assertSame('2026-03-20T00:00:00.000Z', (new GoogleTaskMapper())->toGoogle($task)->getDue());
    }

    /** No link is ever sent: Google's API cannot write one. */
    public function testToGoogleWritesNoLink(): void
    {
        $task = (new Task())->setTitle('T')->setLinks([['type' => 'email', 'description' => 'd', 'link' => 'https://example.com']]);

        self::assertSame([], (new GoogleTaskMapper())->toGoogle($task)->getLinks() ?? []);
    }

    public function testAClearedDueDateIsSentAsAnExplicitNull(): void
    {
        $task = (new Task())->setTitle('T');

        self::assertSame(GoogleModel::NULL_VALUE, (new GoogleTaskMapper())->toGooglePatch($task, ['dueDate'])->getDue());
    }
}
