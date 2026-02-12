<?php

namespace Maggie\Calendar\DataFixtures;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\EventStatus;
use Doctrine\Bundle\FixturesBundle\Fixture;
use Doctrine\Persistence\ObjectManager;

class CalendarFixtures extends Fixture
{
    public function load(ObjectManager $manager): void
    {
        // Default agenda
        $agenda = new Agenda();
        $agenda->setName('Personal');
        $agenda->setDescription('Default personal calendar');
        $agenda->setTimeZone('Europe/Paris');
        $agenda->setColor('#4285F4');
        $agenda->setIsDefault(true);
        $manager->persist($agenda);

        // Work agenda
        $work = new Agenda();
        $work->setName('Work');
        $work->setDescription('Work meetings and deadlines');
        $work->setTimeZone('Europe/Paris');
        $work->setColor('#EA4335');
        $manager->persist($work);

        // Sample timed event — tomorrow at 10:00
        $tomorrow = new \DateTimeImmutable('tomorrow 10:00', new \DateTimeZone('Europe/Paris'));
        $meeting = new Event();
        $meeting->setSummary('Team standup');
        $meeting->setDescription('Daily team sync');
        $meeting->setStartAt($tomorrow);
        $meeting->setEndAt($tomorrow->modify('+30 minutes'));
        $meeting->setTimeZone('Europe/Paris');
        $meeting->setAgenda($work);
        $manager->persist($meeting);

        // Sample all-day event — next Monday
        $nextMonday = new \DateTimeImmutable('next monday', new \DateTimeZone('Europe/Paris'));
        $holiday = new Event();
        $holiday->setSummary('Day off');
        $holiday->setAllDay(true);
        $holiday->setStartAt($nextMonday->setTime(0, 0));
        $holiday->setEndAt($nextMonday->setTime(23, 59, 59));
        $holiday->setTimeZone('Europe/Paris');
        $holiday->setAgenda($agenda);
        $manager->persist($holiday);

        // Sample recurring event — weekly team lunch every Wednesday at 12:00
        $wednesday = new \DateTimeImmutable('next wednesday 12:00', new \DateTimeZone('Europe/Paris'));
        $lunch = new Event();
        $lunch->setSummary('Team lunch');
        $lunch->setDescription('Weekly team lunch');
        $lunch->setLocation('Restaurant Le Petit');
        $lunch->setStartAt($wednesday);
        $lunch->setEndAt($wednesday->modify('+1 hour'));
        $lunch->setTimeZone('Europe/Paris');
        $lunch->setRrule('FREQ=WEEKLY;BYDAY=WE');
        $lunch->setAgenda($work);
        $manager->persist($lunch);

        // Sample event with reminders
        $friday = new \DateTimeImmutable('next friday 14:00', new \DateTimeZone('Europe/Paris'));
        $dentist = new Event();
        $dentist->setSummary('Dentist appointment');
        $dentist->setLocation('Dr. Martin, 15 rue de la Paix');
        $dentist->setStartAt($friday);
        $dentist->setEndAt($friday->modify('+1 hour'));
        $dentist->setTimeZone('Europe/Paris');
        $dentist->setReminders([
            'useDefault' => false,
            'overrides' => [
                ['method' => 'popup', 'minutes' => 60],
                ['method' => 'popup', 'minutes' => 10],
            ],
        ]);
        $dentist->setAgenda($agenda);
        $manager->persist($dentist);

        // Tentative event
        $saturday = new \DateTimeImmutable('next saturday 18:00', new \DateTimeZone('Europe/Paris'));
        $dinner = new Event();
        $dinner->setSummary('Dinner with friends');
        $dinner->setLocation('TBD');
        $dinner->setStartAt($saturday);
        $dinner->setEndAt($saturday->modify('+2 hours'));
        $dinner->setTimeZone('Europe/Paris');
        $dinner->setStatus(EventStatus::Tentative);
        $dinner->setAgenda($agenda);
        $manager->persist($dinner);

        $manager->flush();
    }
}
