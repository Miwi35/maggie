<?php

namespace Maggie\Calendar\Tests\Specification;

use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Entity\ProactionOutcome;
use Maggie\Calendar\Enum\ProactionDelivery;
use Maggie\Calendar\Enum\ProactionStatus;
use Maggie\Calendar\Specification\IsProactionInterrupting;
use PHPUnit\Framework\TestCase;

class IsProactionInterruptingTest extends TestCase
{
    private const COMPLETING = ['executionStatus' => [ProactionStatus::Running, ProactionStatus::Completed]];

    private function completed(?ProactionDelivery $delivery, ?string $link = '/chat?message=01JABC'): Proaction
    {
        return (new Proaction())->setPrompt('Vérifie l\'enveloppe Loisirs')->markRunning()->markCompleted(
            new ProactionOutcome(response: 'Loisirs à 72 %.', delivery: $delivery, notice: 'Loisirs à 72 %.', messageLink: $link),
            new \DateTimeImmutable(),
        );
    }

    public function testARunCompletingWithAnInterruptionSatisfiesIt(): void
    {
        self::assertTrue((new IsProactionInterrupting(self::COMPLETING))->isSatisfiedBy($this->completed(ProactionDelivery::Interrupt)));
    }

    public function testAMessageInTheThreadOrNothingIsNoInterruption(): void
    {
        self::assertFalse((new IsProactionInterrupting(self::COMPLETING))->isSatisfiedBy($this->completed(ProactionDelivery::Thread)));
        self::assertFalse((new IsProactionInterrupting(self::COMPLETING))->isSatisfiedBy($this->completed(ProactionDelivery::None)));
        self::assertFalse((new IsProactionInterrupting(self::COMPLETING))->isSatisfiedBy($this->completed(null)));
    }

    public function testAnInterruptionWithoutAMessageToOpenIsNone(): void
    {
        self::assertFalse((new IsProactionInterrupting(self::COMPLETING))->isSatisfiedBy($this->completed(ProactionDelivery::Interrupt, null)));
    }

    public function testACompletedRunSavedAgainIsNoTransition(): void
    {
        $proaction = $this->completed(ProactionDelivery::Interrupt);

        self::assertFalse((new IsProactionInterrupting([]))->isSatisfiedBy($proaction));
        self::assertFalse((new IsProactionInterrupting(['executionStatus' => [ProactionStatus::Completed, ProactionStatus::Completed]]))->isSatisfiedBy($proaction));
        self::assertFalse((new IsProactionInterrupting(['executionStatus' => [ProactionStatus::Pending, ProactionStatus::Running]]))->isSatisfiedBy($proaction));
    }
}
