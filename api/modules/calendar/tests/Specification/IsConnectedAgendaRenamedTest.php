<?php

namespace Maggie\Calendar\Tests\Specification;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Specification\IsConnectedAgendaRenamed;
use PHPUnit\Framework\TestCase;

class IsConnectedAgendaRenamedTest extends TestCase
{
    private function connected(string $name = 'Ael'): Agenda
    {
        return (new Agenda())->setName($name)->setGoogleCalendarId('ael@group.calendar.google.com');
    }

    public function testRenamingAConnectedAgendaSatisfiesIt(): void
    {
        $specification = new IsConnectedAgendaRenamed(['name' => ['Garde Ael', 'Ael']]);

        self::assertTrue($specification->isSatisfiedBy($this->connected()));
    }

    public function testAnAgendaNotConnectedToGoogleIsNotSent(): void
    {
        $local = (new Agenda())->setName('Perso');

        self::assertFalse((new IsConnectedAgendaRenamed(['name' => ['Privé', 'Perso']]))->isSatisfiedBy($local));
    }

    public function testANameThatCameFromGoogleIsNotSentBack(): void
    {
        $agenda = $this->connected()->adoptGoogleName('Ael');

        self::assertFalse((new IsConnectedAgendaRenamed(['name' => ['Garde Ael', 'Ael']]))->isSatisfiedBy($agenda));
    }

    public function testAUserRenameAfterAGoogleOneIsSentAgain(): void
    {
        $agenda = $this->connected()->adoptGoogleName('Garde Ael')->setName('Ael');

        self::assertTrue((new IsConnectedAgendaRenamed(['name' => ['Garde Ael', 'Ael']]))->isSatisfiedBy($agenda));
    }

    public function testACreationIsNotARename(): void
    {
        self::assertFalse((new IsConnectedAgendaRenamed(['name' => [null, 'Ael']]))->isSatisfiedBy($this->connected()));
    }

    public function testASaveThatLeavesTheNameAloneIsNotARename(): void
    {
        self::assertFalse((new IsConnectedAgendaRenamed(['name' => ['Ael', 'Ael']]))->isSatisfiedBy($this->connected()));
        self::assertFalse((new IsConnectedAgendaRenamed(['color' => ['#fff', '#000']]))->isSatisfiedBy($this->connected()));
        self::assertFalse((new IsConnectedAgendaRenamed([]))->isSatisfiedBy($this->connected()));
    }
}
