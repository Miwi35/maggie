<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Enum\PeerDebtDirection;
use Maggie\Finance\Enum\PeerDebtStatus;
use Maggie\Finance\Repository\ContactRepository;
use Maggie\Finance\UseCase\ListPeerDebts;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Uid\Ulid;

/**
 * The créances and dettes, filtered by `status` (open|settled), `direction`
 * (owed_to_me|i_owe) and `contact` (a contact id), each with its shares and the
 * transfers that gave it back (MAG-455).
 */
final class PeerDebtsController
{
    public function __construct(
        private readonly Security $security,
        private readonly ListPeerDebts $listPeerDebts,
        private readonly ContactRepository $contactRepository,
    ) {
    }

    #[Route('/api/finance/peer-debts', name: 'api_finance_peer_debts', methods: ['GET'])]
    public function __invoke(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $status = $this->filter($request, 'status');
        $direction = $this->filter($request, 'direction');
        $contactId = $this->filter($request, 'contact');

        $wantedStatus = null === $status ? null : PeerDebtStatus::tryFrom($status);
        if (null !== $status && null === $wantedStatus) {
            return new JsonResponse(['error' => 'status must be open or settled.'], Response::HTTP_BAD_REQUEST);
        }
        $wantedDirection = null === $direction ? null : PeerDebtDirection::tryFrom($direction);
        if (null !== $direction && null === $wantedDirection) {
            return new JsonResponse(['error' => 'direction must be owed_to_me or i_owe.'], Response::HTTP_BAD_REQUEST);
        }
        if (null !== $contactId && !Ulid::isValid($contactId)) {
            return new JsonResponse(['error' => 'contact must be a contact id.'], Response::HTTP_BAD_REQUEST);
        }

        $contact = null;
        if (null !== $contactId) {
            $contact = $this->contactRepository->findOneOwned($contactId, $user);
            if (null === $contact) {
                return new JsonResponse(['error' => "Contact not found: {$contactId}"], Response::HTTP_NOT_FOUND);
            }
        }

        return new JsonResponse(['peerDebts' => $this->listPeerDebts->execute($user, $wantedStatus, $wantedDirection, $contact)]);
    }

    private function filter(Request $request, string $name): ?string
    {
        $value = $request->query->all()[$name] ?? null;
        if (null === $value || '' === $value) {
            return null;
        }

        // A list (`status[]=…`) is no filter: it falls through to the refusal.
        return \is_string($value) ? $value : '?';
    }
}
