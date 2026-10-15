<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Exception\EnvelopeSplitException;
use Maggie\Finance\Message\SplitEnvelopeCommand;
use Maggie\Finance\Repository\EnvelopeRepository;
use Maggie\Finance\UseCase\GetEnvelopeSplit;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Uid\Ulid;

/** Shares the amount of a mother envelope between the envelopes of its sub-categories. */
final class EnvelopeSplitController
{
    public function __construct(
        private readonly Security $security,
        private readonly EnvelopeRepository $envelopeRepository,
        private readonly MessageBusInterface $bus,
        private readonly GetEnvelopeSplit $getEnvelopeSplit,
    ) {
    }

    #[Route('/api/finance/envelopes/{id}/split', name: 'api_finance_envelope_split', methods: ['PUT'])]
    public function __invoke(Request $request, string $id): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $envelope = Ulid::isValid($id) ? $this->envelopeRepository->findOneBy(['id' => $id, 'user' => $user]) : null;
        if (null === $envelope) {
            return new JsonResponse(['error' => 'Envelope not found'], Response::HTTP_NOT_FOUND);
        }

        $content = $request->getContent();
        try {
            $body = '' === $content ? [] : json_decode($content, true, 512, \JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            return new JsonResponse(['error' => 'The body must be valid JSON'], Response::HTTP_BAD_REQUEST);
        }

        $children = \is_array($body) ? ($body['children'] ?? null) : null;
        if (!\is_array($children) || !array_is_list($children)) {
            return new JsonResponse(['error' => 'children is required: a list of {envelopeId, amountCents}'], Response::HTTP_BAD_REQUEST);
        }

        $amounts = [];
        foreach ($children as $child) {
            $envelopeId = \is_array($child) ? ($child['envelopeId'] ?? null) : null;
            $amountCents = \is_array($child) ? ($child['amountCents'] ?? null) : null;

            if (!\is_string($envelopeId) || !Ulid::isValid($envelopeId)) {
                return new JsonResponse(['error' => 'Each child needs an envelopeId'], Response::HTTP_BAD_REQUEST);
            }
            if (!\is_int($amountCents) || $amountCents < 0) {
                return new JsonResponse(['error' => 'Each child needs amountCents, a positive integer'], Response::HTTP_BAD_REQUEST);
            }
            $key = (new Ulid($envelopeId))->toBase32();
            if (isset($amounts[$key])) {
                return new JsonResponse(['error' => 'An envelope can only appear once'], Response::HTTP_BAD_REQUEST);
            }
            $amounts[$key] = $amountCents;
        }

        try {
            $this->bus->dispatch(new SplitEnvelopeCommand(
                userId: (string) $user->getId(),
                envelopeId: (string) $envelope->getId(),
                amountsByEnvelopeId: $amounts,
            ));
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            if (!$cause instanceof EnvelopeSplitException) {
                throw $e;
            }

            return new JsonResponse(
                ['error' => $cause->getMessage()] + $this->getEnvelopeSplit->execute($envelope),
                Response::HTTP_BAD_REQUEST,
            );
        }

        return new JsonResponse($this->getEnvelopeSplit->execute($envelope));
    }
}
