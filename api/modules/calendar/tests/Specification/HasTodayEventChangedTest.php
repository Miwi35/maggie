<?php

namespace Maggie\Calendar\Tests\Specification;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Specification\HasTodayEventChanged;
use PHPUnit\Framework\TestCase;

/** MAG-656. « Now » is 10:00 on Tuesday 3 November 2026 in Paris. */
class HasTodayEventChangedTest extends TestCase
{
    private \DateTimeImmutable $now;

    protected function setUp(): void
    {
        $this->now = new \DateTimeImmutable('2026-11-03 10:00:00', new \DateTimeZone('Europe/Paris'));
    }

    private function at(string $parisTime): \DateTimeImmutable
    {
        return new \DateTimeImmutable($parisTime, new \DateTimeZone('Europe/Paris'));
    }

    private function event(string $start = '2026-11-03 14:00:00', ?string $location = '5 allée du Sourire Fictif', ?Agenda $agenda = null): Event
    {
        $event = (new Event())->setSummary('Dentiste')->setLocation($location);
        $event->setAgenda($agenda ?? (new Agenda())->setName('Perso'));

        return $event->scheduleTimed($this->at($start), $this->at($start)->modify('+1 hour'));
    }

    public function testATodayEventCreatedWithALocationSatisfiesIt(): void
    {
        self::assertTrue(HasTodayEventChanged::created($this->now)->isSatisfiedBy($this->event()));
    }

    public function testATodayEventCreatedWithoutALocationDoesNot(): void
    {
        self::assertFalse(HasTodayEventChanged::created($this->now)->isSatisfiedBy($this->event(location: null)));
        self::assertFalse(HasTodayEventChanged::created($this->now)->isSatisfiedBy($this->event(location: '  ')));
    }

    public function testAnEventCreatedForAnotherDayDoesNot(): void
    {
        self::assertFalse(HasTodayEventChanged::created($this->now)->isSatisfiedBy($this->event('2026-11-04 14:00:00')));
    }

    public function testTheDayIsReadInParisNotInUtc(): void
    {
        // 00:30 on the 4th in Paris is still the 3rd in UTC; 00:30 on the 3rd in Paris is the 2nd in UTC.
        self::assertFalse(HasTodayEventChanged::created($this->now)->isSatisfiedBy($this->event('2026-11-04 00:30:00')));
        self::assertTrue(HasTodayEventChanged::created($this->now)->isSatisfiedBy($this->event('2026-11-03 00:30:00')));
    }

    public function testMovingATodayEventSatisfiesIt(): void
    {
        $changeSet = ['startAt' => [$this->at('2026-11-03 14:00:00'), $this->at('2026-11-03 15:00:00')]];

        self::assertTrue(HasTodayEventChanged::updated($changeSet, $this->now)->isSatisfiedBy($this->event('2026-11-03 15:00:00')));
    }

    public function testChangingTheLocationOfATodayEventSatisfiesIt(): void
    {
        $changeSet = ['location' => ['Ancien lieu', '5 allée du Sourire Fictif']];

        self::assertTrue(HasTodayEventChanged::updated($changeSet, $this->now)->isSatisfiedBy($this->event()));
    }

    public function testMovingAnEventOutOfTodayOrIntoItSatisfiesIt(): void
    {
        $out = ['startAt' => [$this->at('2026-11-03 14:00:00'), $this->at('2026-11-05 14:00:00')]];
        $into = ['startAt' => [$this->at('2026-11-05 14:00:00'), $this->at('2026-11-03 14:00:00')]];

        self::assertTrue(HasTodayEventChanged::updated($out, $this->now)->isSatisfiedBy($this->event('2026-11-05 14:00:00')));
        self::assertTrue(HasTodayEventChanged::updated($into, $this->now)->isSatisfiedBy($this->event('2026-11-03 14:00:00')));
    }

    public function testChangingTheLocationOfAnotherDaysEventDoesNot(): void
    {
        $changeSet = ['location' => ['Ancien lieu', 'Nouveau lieu']];

        self::assertFalse(HasTodayEventChanged::updated($changeSet, $this->now)->isSatisfiedBy($this->event('2026-11-05 14:00:00')));
    }

    public function testMovingAnEventBetweenTwoOtherDaysDoesNot(): void
    {
        $changeSet = ['startAt' => [$this->at('2026-11-05 14:00:00'), $this->at('2026-11-06 14:00:00')]];

        self::assertFalse(HasTodayEventChanged::updated($changeSet, $this->now)->isSatisfiedBy($this->event('2026-11-06 14:00:00')));
    }

    public function testChangingOnlyTheTitleOrTheDescriptionDoesNot(): void
    {
        $changeSet = ['summary' => ['Dentiste', 'Dentiste Ael'], 'description' => [null, 'Détartrage']];

        self::assertFalse(HasTodayEventChanged::updated($changeSet, $this->now)->isSatisfiedBy($this->event()));
    }

    public function testSavingTheSameStartAgainIsNotAMove(): void
    {
        // Doctrine reports a new object of the same instant as a change.
        $changeSet = ['startAt' => [$this->at('2026-11-03 14:00:00'), $this->at('2026-11-03 14:00:00')]];

        self::assertFalse(HasTodayEventChanged::updated($changeSet, $this->now)->isSatisfiedBy($this->event()));
    }

    public function testOnlyACreationWithALocationOrAnUpdateAddingOneAddsALocation(): void
    {
        $added = ['location' => [null, 'Nouveau lieu']];
        $changed = ['location' => ['Ancien lieu', 'Nouveau lieu']];
        $removed = ['location' => ['Ancien lieu', null]];
        $moved = ['startAt' => [$this->at('2026-11-03 14:00:00'), $this->at('2026-11-03 15:00:00')]];

        self::assertTrue(HasTodayEventChanged::created($this->now)->addsLocation($this->event()));
        self::assertFalse(HasTodayEventChanged::created($this->now)->addsLocation($this->event(location: null)));
        self::assertTrue(HasTodayEventChanged::updated($added, $this->now)->addsLocation($this->event()));
        self::assertTrue(HasTodayEventChanged::updated(['location' => ['  ', 'Lieu']], $this->now)->addsLocation($this->event()));
        self::assertFalse(HasTodayEventChanged::updated($changed, $this->now)->addsLocation($this->event()));
        self::assertFalse(HasTodayEventChanged::updated($removed, $this->now)->addsLocation($this->event(location: null)));
        self::assertFalse(HasTodayEventChanged::updated($moved, $this->now)->addsLocation($this->event()));
        self::assertFalse(HasTodayEventChanged::removed($this->now)->addsLocation($this->event()));
    }

    public function testAnEventWithoutALocationBeforeAndAfterTriggersNothing(): void
    {
        $moved = ['startAt' => [$this->at('2026-11-03 14:00:00'), $this->at('2026-11-03 15:00:00')]];
        $blank = ['location' => ['', null], 'startAt' => $moved['startAt']];

        self::assertFalse(HasTodayEventChanged::updated($moved, $this->now)->isSatisfiedBy($this->event('2026-11-03 15:00:00', null)));
        self::assertFalse(HasTodayEventChanged::updated($blank, $this->now)->isSatisfiedBy($this->event('2026-11-03 15:00:00', null)));
        self::assertFalse(HasTodayEventChanged::removed($this->now)->isSatisfiedBy($this->event(location: null)));
    }

    public function testLosingTheLocationOfATodayEventStillTriggers(): void
    {
        $changeSet = ['location' => ['Ancien lieu', null]];

        self::assertTrue(HasTodayEventChanged::updated($changeSet, $this->now)->isSatisfiedBy($this->event(location: null)));
    }

    public function testRemovingATodayEventSatisfiesIt(): void
    {
        self::assertTrue(HasTodayEventChanged::removed($this->now)->isSatisfiedBy($this->event()));
    }

    public function testRemovingAnotherDaysEventDoesNot(): void
    {
        self::assertFalse(HasTodayEventChanged::removed($this->now)->isSatisfiedBy($this->event('2026-11-04 14:00:00')));
    }

    public function testAnAllDayEventHasNoDepartureToPlan(): void
    {
        $event = (new Event())->setSummary('Vacances')->setLocation('Rennes');
        $event->setAgenda((new Agenda())->setName('Perso'));
        $event->scheduleAllDay(new \DateTimeImmutable('2026-11-03'));

        self::assertFalse(HasTodayEventChanged::created($this->now)->isSatisfiedBy($event));
        self::assertFalse(HasTodayEventChanged::removed($this->now)->isSatisfiedBy($event));
    }

    public function testAnEventOfAModuleAgendaDoesNot(): void
    {
        $maggie = (new Agenda())->setName('Maggie')->setModule(Agenda::MODULE_AGENT);

        self::assertFalse(HasTodayEventChanged::created($this->now)->isSatisfiedBy($this->event(agenda: $maggie)));
        self::assertFalse(HasTodayEventChanged::removed($this->now)->isSatisfiedBy($this->event(agenda: $maggie)));
    }

    public function testAProactionDoesNot(): void
    {
        $maggie = (new Agenda())->setName('Maggie')->setModule(Agenda::MODULE_AGENT);
        $proaction = (new Proaction())->setPrompt('Replanifier les trajets')->setSummary('Replanifier les trajets')->setLocation('Rennes');
        $proaction->setAgenda($maggie);
        $proaction->scheduleAt($this->now);

        self::assertFalse(HasTodayEventChanged::created($this->now)->isSatisfiedBy($proaction));
        self::assertFalse(HasTodayEventChanged::updated(['startAt' => [null, $this->now]], $this->now)->isSatisfiedBy($proaction));
    }
}
