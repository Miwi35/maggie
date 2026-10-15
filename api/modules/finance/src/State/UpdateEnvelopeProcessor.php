<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Enum\BudgetMode;
use Maggie\Finance\Message\UpdateEnvelopeCommand;
use Maggie\Finance\Service\SeasonalAmounts;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\HttpKernel\Exception\UnprocessableEntityHttpException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Envelope, Envelope> */
class UpdateEnvelopeProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Envelope
    {
        /** @var Envelope|null $previous */
        $previous = $context['previous_data'] ?? null;
        if (null !== $previous && $previous->isOneOff() !== $data->isOneOff()) {
            throw new UnprocessableEntityHttpException('An envelope cannot change between one-off and monthly or annual: create a new one.');
        }
        if ($data->isOneOff() && null !== $data->getYear()) {
            throw new UnprocessableEntityHttpException('A one-off envelope has no period.');
        }

        $seasonalAmounts = $this->seasonalAmounts($data);

        $stamped = $this->bus->dispatch(new UpdateEnvelopeCommand(
            userId: (string) $data->getUser()->getId(),
            envelopeId: (string) $data->getId(),
            categoryId: null !== $data->getCategory() ? (string) $data->getCategory()->getId() : null,
            amountCents: $data->getAmountCents(),
            year: $data->getYear(),
            mode: $data->getMode()->value,
            month: $data->getMonth(),
            currency: $data->getCurrency(),
            name: $data->getName(),
            alertThresholdPercent: $data->getAlertThresholdPercent(),
            setsAlertThreshold: true,
            seasonalAmounts: $seasonalAmounts,
        ));

        return $stamped->last(HandledStamp::class)->getResult();
    }

    /**
     * A malformed or overlapping season is the client's mistake, told apart
     * from the envelope's other rules (422): 400, before anything is written.
     *
     * @return list<array{fromMonth: int, toMonth: int, amountCents: int}>
     */
    private function seasonalAmounts(Envelope $envelope): array
    {
        try {
            $seasons = SeasonalAmounts::normalize($envelope->getSeasonalAmounts());
        } catch (\InvalidArgumentException $e) {
            throw new BadRequestHttpException($e->getMessage(), $e);
        }
        if ([] !== $seasons && BudgetMode::Monthly !== $envelope->getMode()) {
            throw new BadRequestHttpException('Only a monthly envelope has seasonal amounts.');
        }

        return $seasons;
    }
}
