<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\Service;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Service\AgendaChoiceKind;
use Maggie\Calendar\Service\AgendaSuggester;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Enum\MealSlot;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * A planned meal is not evidence about where an appointment belongs (MAG-150).
 *
 * `Meal extends Event`, so the agenda deduction's own query returns meals among the
 * events, and `CreateMealHandler` files them in a « Repas » agenda it creates for itself.
 * Left in, one planned lunch is enough to make « Repas » a candidate for « Déjeuner avec
 * Paul » — and since both reach the same score, Maggie would ask whether an appointment
 * goes in the meal planner. MAG-150 leaves those dedicated agendas alone by its own
 * wording, and nobody chooses them one event at a time.
 *
 * Here rather than in the calendar module because the dependency only points this way:
 * cookbook knows about calendar, not the reverse.
 */
final class MealIsNotAnAgendaHabitTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    private User $user;
    private Agenda $work;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->purgeDatabase();

        $this->user = new User();
        $this->user->setEmail('meal-habit@example.com');
        $this->user->setGoogleId('google-meal-habit');
        $this->user->setName('Meal Habit');

        $personal = $this->agenda('Perso', true);
        $this->work = $this->agenda('Boulot', false);
        $meals = $this->agenda('Repas', false);

        // Paul is only ever seen at work, and never over a meal whose title says so: the
        // word « déjeuner » has to come from the meal planner alone for this to prove
        // anything.
        $this->event('Point avec Paul', '-7 days', $this->work);
        $this->event('Café avec Paul', '-14 days', $this->work);

        // One planned lunch is enough: « déjeuner » is then held by « Repas » alone, so its
        // share is whole and it scores exactly what « Paul » scores for « Boulot ».
        $meal = new Meal();
        $meal->setAgenda($meals);
        $meal->setSlot(MealSlot::Lunch);
        $meal->setDate(new \DateTimeImmutable('-1 day'));
        $meal->setSummary('Déjeuner : Pâtes à la tomate');

        $em = $this->em();
        $em->persist($this->user);
        foreach ([$personal, $this->work, $meals, $meal] as $entity) {
            $em->persist($entity);
        }
        $em->flush();
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function agenda(string $name, bool $default): Agenda
    {
        $agenda = new Agenda();
        $agenda->setName($name);
        $agenda->setUser($this->user);
        $agenda->setIsDefault($default);

        return $agenda;
    }

    private function event(string $summary, string $when, Agenda $agenda): void
    {
        $start = new \DateTimeImmutable($when.' 10:00', new \DateTimeZone('Europe/Paris'));

        $event = new Event();
        $event->setSummary($summary);
        $event->setStartAt($start);
        $event->setEndAt($start->modify('+1 hour'));
        $event->setAgenda($agenda);

        $this->em()->persist($event);
    }

    public function testAPlannedMealNeverMakesTheMealAgendaACandidate(): void
    {
        $choice = self::getContainer()->get(AgendaSuggester::class)->suggest($this->user, 'Déjeuner avec Paul');

        self::assertSame(AgendaChoiceKind::Deduced, $choice->kind, 'the meal turned the deduction into a question');
        self::assertSame('Boulot', $choice->agenda?->getName());
        self::assertSame(
            ['Boulot'],
            array_map(static fn ($candidate) => $candidate->agenda->getName(), $choice->candidates),
        );
    }
}
