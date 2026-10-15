<?php

namespace Maggie\Calendar\Tests\Entity;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Enum\ProactionStatus;
use Maggie\Core\Elasticsearch\Hydrator\ElasticsearchEntityHydrator;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\TestCase;

/**
 * `/api/proactions` is rebuilt from the Elasticsearch document alone: what the
 * document leaves out never reaches the detail of a proaction (MAG-328).
 */
class ProactionSearchDocumentTest extends TestCase
{
    private Agenda $agenda;

    protected function setUp(): void
    {
        $user = new User();
        $user->setEmail('maggie@example.com');
        $user->setGoogleId('google-maggie');
        $user->setName('Maggie');

        $this->agenda = new Agenda();
        $this->agenda->setName('Maggie');
        $this->agenda->setUser($user);
    }

    private function proaction(): Proaction
    {
        $proaction = new Proaction();
        $proaction->setPrompt('Rappelle-moi de sortir le linge');
        $proaction->setSummary(Proaction::summaryOf('Rappelle-moi de sortir le linge'));
        $proaction->setAgenda($this->agenda);
        $proaction->scheduleAt(new \DateTimeImmutable('2026-03-17T08:30:00+00:00'));

        return $proaction;
    }

    private function hydrate(Proaction $proaction): Proaction
    {
        $em = $this->createStub(EntityManagerInterface::class);
        $em->method('getReference')->willReturn($this->agenda);

        $source = $proaction->toSearchDocument();
        $source['id'] = (string) $proaction->getId();

        $hydrated = (new ElasticsearchEntityHydrator($em))->hydrate($source, Proaction::class);
        self::assertInstanceOf(Proaction::class, $hydrated);

        return $hydrated;
    }

    public function testTheResponseOfACompletedProactionSurvivesTheRoundTrip(): void
    {
        $proaction = $this->proaction()->markCompleted('Le linge est sorti.', new \DateTimeImmutable('2026-03-17T08:31:00+00:00'));

        $hydrated = $this->hydrate($proaction);

        self::assertSame(ProactionStatus::Completed, $hydrated->getExecutionStatus());
        self::assertSame('Le linge est sorti.', $hydrated->getResponse());
        self::assertNull($hydrated->getError());
        self::assertSame('Rappelle-moi de sortir le linge', $hydrated->getPrompt());
    }

    public function testTheErrorOfAFailedProactionSurvivesTheRoundTrip(): void
    {
        $proaction = $this->proaction()->markFailed('Le calendrier est injoignable', new \DateTimeImmutable('2026-03-17T08:31:00+00:00'));

        $hydrated = $this->hydrate($proaction);

        self::assertSame(ProactionStatus::Failed, $hydrated->getExecutionStatus());
        self::assertSame('Le calendrier est injoignable', $hydrated->getError());
        self::assertNull($hydrated->getResponse());
    }
}
