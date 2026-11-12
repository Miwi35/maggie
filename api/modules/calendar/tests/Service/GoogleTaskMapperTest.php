<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Service;

use Google\Model as GoogleModel;
use Google\Service\Tasks\Task as GoogleTask;
use Google\Service\Tasks\TaskLinks;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Enum\TaskMoment;
use Maggie\Calendar\Service\GoogleTaskMapper;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\TestCase;

/** Google Tasks' fields, both ways (MAG-404); Google's `due` is the scheduled day (MAG-822). */
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
        self::assertSame('2026-03-20', $task->getScheduledDate()?->format('Y-m-d'));
        self::assertSame('00000000000000000003', $task->getPosition());
        self::assertSame([['type' => 'email', 'description' => 'Le mail', 'link' => 'https://mail.google.com/mail/#all/abc']], $task->getLinks());
    }

    public function testATaskWithoutLinksHasNone(): void
    {
        $googleTask = new GoogleTask();
        $googleTask->setId('g-1');
        $googleTask->setTitle('Rien');
        $googleTask->setStatus('needsAction');

        $existing = (new Task())->setLinks([['type' => 'email', 'description' => '', 'link' => 'x']]);
        $task = (new GoogleTaskMapper())->fromGoogle($googleTask, new User(), $existing);

        self::assertSame([], $task->getLinks());
    }

    public function testGoogleDueBecomesScheduledDate(): void
    {
        $task = (new GoogleTaskMapper())->fromGoogle($this->googleTask('2026-11-20T00:00:00.000Z'), new User());

        self::assertSame('2026-11-20', $task->getScheduledDate()?->format('Y-m-d'));
        self::assertNull($task->getDueDate());
    }

    /** Google carries no time of day: a new day keeps the moment, the time and the reminders. */
    public function testDueChangeKeepsMomentAndTime(): void
    {
        $existing = (new Task())->schedule(new \DateTimeImmutable('2026-11-20'), TaskMoment::Time, '18:00', [15]);

        $task = (new GoogleTaskMapper())->fromGoogle($this->googleTask('2026-11-21T00:00:00.000Z'), new User(), $existing);

        self::assertSame('2026-11-21', $task->getScheduledDate()?->format('Y-m-d'));
        self::assertSame(TaskMoment::Time, $task->getScheduledMoment());
        self::assertSame('18:00', $task->getScheduledTime());
        self::assertSame([15], $task->getReminders());
    }

    public function testDueRemovedClearsSchedule(): void
    {
        $existing = (new Task())->schedule(new \DateTimeImmutable('2026-11-20'), TaskMoment::Time, '18:00', [0]);

        $task = (new GoogleTaskMapper())->fromGoogle($this->googleTask(null), new User(), $existing);

        self::assertNull($task->getScheduledDate());
        self::assertNull($task->getScheduledMoment());
        self::assertNull($task->getScheduledTime());
        self::assertSame([], $task->getReminders());
    }

    /** The deadline is Maggie's own: a pull, with or without a day, never touches it. */
    public function testPullLeavesDueDateAlone(): void
    {
        $mapper = new GoogleTaskMapper();
        $existing = (new Task())->setDueDate(new \DateTimeImmutable('2026-11-30'));

        $mapper->fromGoogle($this->googleTask('2026-11-20T00:00:00.000Z'), new User(), $existing);
        self::assertSame('2026-11-30', $existing->getDueDate()?->format('Y-m-d'));

        $mapper->fromGoogle($this->googleTask(null), new User(), $existing);
        self::assertSame('2026-11-30', $existing->getDueDate()?->format('Y-m-d'));
    }

    /** The estimate and the first step are Maggie's own (MAG-497): a change on Google loses neither, and Google is never sent them. */
    public function testAChangeOnGoogleKeepsTheEstimateAndTheFirstStep(): void
    {
        $googleTask = new GoogleTask();
        $googleTask->setId('g-1');
        $googleTask->setTitle('Rappeler le garage, renommée sur Google');
        $googleTask->setNotes('Notes changées sur Google');
        $googleTask->setStatus('needsAction');

        $existing = (new Task())->setTitle('Rappeler le garage')->setEstimatedMinutes(10)->setFirstStep('Trouver le numéro');
        $task = (new GoogleTaskMapper())->fromGoogle($googleTask, new User(), $existing);

        self::assertSame('Rappeler le garage, renommée sur Google', $task->getTitle());
        self::assertSame(10, $task->getEstimatedMinutes());
        self::assertSame('Trouver le numéro', $task->getFirstStep());

        $sent = (new GoogleTaskMapper())->toGoogle($task)->toSimpleObject();
        self::assertStringNotContainsString('Trouver le numéro', (string) json_encode($sent));
    }

    /** The scheduled day goes as Google's midnight UTC, whatever zone the object holds. */
    public function testToGoogleSendsTheScheduledDayAtMidnightUtc(): void
    {
        $task = (new Task())->setTitle('T')->schedule(new \DateTimeImmutable('2026-03-20', new \DateTimeZone('Pacific/Kiritimati')), null, null, []);

        self::assertSame('2026-03-20T00:00:00.000Z', (new GoogleTaskMapper())->toGoogle($task)->getDue());
    }

    public function testToGoogleSendsNoDueWithoutScheduledDate(): void
    {
        $task = (new Task())->setTitle('T')->setDueDate(new \DateTimeImmutable('2026-03-20'));

        self::assertNull((new GoogleTaskMapper())->toGoogle($task)->getDue());
    }

    public function testPatchSendsScheduledDateAsDue(): void
    {
        $task = (new Task())->setTitle('T')->setDueDate(new \DateTimeImmutable('2026-11-30'))
            ->schedule(new \DateTimeImmutable('2026-11-22'), TaskMoment::Evening, null, []);
        $mapper = new GoogleTaskMapper();

        self::assertSame('2026-11-22T00:00:00.000Z', $mapper->toGooglePatch($task, ['scheduledDate'])->getDue());
        self::assertNull($mapper->toGooglePatch($task, ['title'])->getDue());
    }

    /** No link is ever sent: Google's API cannot write one. */
    public function testToGoogleWritesNoLink(): void
    {
        $task = (new Task())->setTitle('T')->setLinks([['type' => 'email', 'description' => 'd', 'link' => 'https://example.com']]);

        self::assertSame([], (new GoogleTaskMapper())->toGoogle($task)->getLinks() ?? []);
    }

    public function testAClearedScheduledDateIsSentAsAnExplicitNull(): void
    {
        $task = (new Task())->setTitle('T')->setDueDate(new \DateTimeImmutable('2026-11-30'));

        self::assertSame(GoogleModel::NULL_VALUE, (new GoogleTaskMapper())->toGooglePatch($task, ['scheduledDate'])->getDue());
    }

    private function googleTask(?string $due): GoogleTask
    {
        $googleTask = new GoogleTask();
        $googleTask->setId('g-1');
        $googleTask->setTitle('Appeler le garage');
        $googleTask->setStatus('needsAction');
        $googleTask->setDue($due);

        return $googleTask;
    }
}
