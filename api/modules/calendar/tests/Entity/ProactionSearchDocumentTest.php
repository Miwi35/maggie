<?php

namespace Maggie\Calendar\Tests\Entity;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Entity\ProactionOutcome;
use Maggie\Calendar\Enum\ProactionDelivery;
use Maggie\Calendar\Enum\ProactionKind;
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
        $proaction = $this->proaction()->markCompleted(new ProactionOutcome('Le linge est sorti.'), new \DateTimeImmutable('2026-03-17T08:31:00+00:00'));

        $hydrated = $this->hydrate($proaction);

        self::assertSame(ProactionStatus::Completed, $hydrated->getExecutionStatus());
        self::assertSame('Le linge est sorti.', $hydrated->getResponse());
        self::assertNull($hydrated->getError());
        self::assertSame('Rappelle-moi de sortir le linge', $hydrated->getPrompt());
    }

    public function testTheKindAndTraceOfACheckSurviveTheRoundTrip(): void
    {
        // A check's delivery, topic and verdict are what the next check reads back (MAG-886).
        $proaction = $this->proaction()->setKind(ProactionKind::Check)->markCompleted(
            ProactionOutcome::fromInput('Loisirs à 72 %.', 'thread', 'enveloppe-loisirs', 'low', 'solid', null, null, 'Loisirs à 72 %.', '/chat?message=01JABC'),
            new \DateTimeImmutable('2026-03-17T08:31:00+00:00'),
        );

        $hydrated = $this->hydrate($proaction);

        self::assertSame(ProactionKind::Check, $hydrated->getKind());
        self::assertSame(ProactionDelivery::Thread, $hydrated->getDelivery());
        self::assertSame('enveloppe-loisirs', $hydrated->getTopicKey());
        self::assertSame(['impact' => 'low', 'evidence' => 'solid'], $hydrated->getVerdict());
        self::assertSame('Loisirs à 72 %.', $hydrated->getNotice());
        self::assertSame('/chat?message=01JABC', $hydrated->getMessageLink());
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
