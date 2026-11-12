<?php

declare(strict_types=1);

namespace Maggie\Calendar\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Period;
use Symfony\Component\Validator\Validator\ValidatorInterface;

class SavePeriod
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly ValidatorInterface $validator,
    ) {
    }

    /**
     * The entity's own rules, checked here too: Maggie's tool does not go
     * through API Platform's validation, and nothing invalid is written.
     *
     * @throws \DomainException
     */
    public function execute(Period $period): Period
    {
        $violations = $this->validator->validate($period);
        if (\count($violations) > 0) {
            $messages = [];
            foreach ($violations as $violation) {
                $messages[] = ('' !== $violation->getPropertyPath() ? $violation->getPropertyPath().': ' : '').$violation->getMessage();
            }

            // A refused update leaves the stored period as it was, for whatever flushes next.
            if ($this->em->contains($period)) {
                $this->em->refresh($period);
            }

            throw new \DomainException(implode(' ', $messages).' Nothing was saved.');
        }

        $this->em->persist($period);
        $this->em->flush();

        return $period;
    }
}
