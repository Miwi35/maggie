<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Exception\IncompatibleCategoryException;
use Symfony\Component\Validator\Validator\ValidatorInterface;

/**
 * What a recurring operation must satisfy before it is written, whichever
 * door it came through: the REST body is validated by API Platform, an MCP
 * call is not, so the handlers ask here.
 */
class RecurringOperationGuard
{
    public function __construct(
        private readonly ValidatorInterface $validator,
        private readonly TransactionNatureGuard $natureGuard,
    ) {
    }

    /**
     * @throws \DomainException              when a constraint of the entity fails
     * @throws IncompatibleCategoryException when the category contradicts the sign of the amount
     */
    public function assertValid(RecurringOperation $operation): void
    {
        $violations = $this->validator->validate($operation);

        if (\count($violations) > 0) {
            $messages = [];
            foreach ($violations as $violation) {
                $messages[] = $violation->getPropertyPath().': '.$violation->getMessage();
            }

            throw new \DomainException(implode(' ', $messages));
        }

        $this->natureGuard->assertCompatible($operation->getReferenceAmountCents(), $operation->getCategory());
    }

    /** An ISO date, at midnight. */
    public static function date(string $value, string $field): \DateTimeImmutable
    {
        $date = \DateTimeImmutable::createFromFormat('!Y-m-d', $value);

        if (false === $date || $date->format('Y-m-d') !== $value) {
            throw new \DomainException("{$field} must be an ISO date (YYYY-MM-DD), got \"{$value}\".");
        }

        return $date;
    }
}
