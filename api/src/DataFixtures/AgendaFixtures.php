<?php

namespace App\DataFixtures;

use App\Entity\Calendar;
use App\Entity\Event;
use App\Entity\EventStatus;
use Doctrine\Bundle\FixturesBundle\Fixture;
use Doctrine\Persistence\ObjectManager;

class AgendaFixtures extends Fixture
{
    public function load(ObjectManager $manager): void
    {
        // Default calendar
        $calendar = new Calendar();
        $calendar->setName('Personal');
        $calendar->setDescription('Default personal calendar');
        $calendar->setTimeZone('Europe/Paris');
        $calendar->setColor('#4285F4');
        $calendar->setIsDefault(true);
        $manager->persist($calendar);

        // Work calendar
        $work = new Calendar();
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
        $meeting->setCalendar($work);
        $manager->persist($meeting);

        // Sample all-day event — next Monday
        $nextMonday = new \DateTimeImmutable('next monday', new \DateTimeZone('Europe/Paris'));
        $holiday = new Event();
        $holiday->setSummary('Day off');
        $holiday->setAllDay(true);
        $holiday->setStartAt($nextMonday->setTime(0, 0));
        $holiday->setEndAt($nextMonday->setTime(23, 59, 59));
        $holiday->setTimeZone('Europe/Paris');
        $holiday->setCalendar($calendar);
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
        $lunch->setCalendar($work);
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
        $dentist->setCalendar($calendar);
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
        $dinner->setCalendar($calendar);
        $manager->persist($dinner);

        $manager->flush();
    }
}
