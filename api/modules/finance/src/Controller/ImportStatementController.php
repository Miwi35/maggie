<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Import\CsvStatementParser;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\UseCase\ImportStatement;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Uid\Ulid;

/**
 * Files a bank CSV export on one account, from the admin.
 *
 * The same two steps as `app:finance:import`: a rehearsal that writes nothing
 * and reports what it would do, then the real pass once someone has read the
 * report. The file travels twice because nothing is kept between the two
 * calls — re-sending it is safe by construction, the import recognises what is
 * already stored (see {@see ImportStatement}).
 */
final class ImportStatementController
{
    /**
     * A year of movements is a few tens of kilobytes; past this it is not a
     * statement, and a parse would only waste memory finding out.
     */
    private const MAX_BYTES = 2 * 1024 * 1024;

    public function __construct(
        private readonly Security $security,
        private readonly CsvStatementParser $parser,
        private readonly ImportStatement $importStatement,
        private readonly AccountRepository $accountRepository,
    ) {
    }

    #[Route('/api/finance/import-statement', name: 'api_finance_import_statement', methods: ['POST'])]
    public function __invoke(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $file = $request->files->get('file');
        if (null === $file) {
            return $this->badRequest('Joignez le fichier à importer.');
        }

        if (!$file->isValid()) {
            // A file PHP itself turned away for its size is not a file to
            // retry: told "it did not arrive in one piece", the owner would
            // send it again, for ever. It is refused for the same reason as
            // the ceiling below — the runtime lets 64 Mo through, so anything
            // it rejects is far past 2 Mo — so it is refused in the same words.
            return $this->badRequest(\in_array(
                $file->getError(),
                [\UPLOAD_ERR_INI_SIZE, \UPLOAD_ERR_FORM_SIZE],
                true,
            )
                ? self::tooLarge()
                : "Le fichier n'est pas arrivé en entier. Réessayez.");
        }

        $size = $file->getSize();
        if (false !== $size && $size > self::MAX_BYTES) {
            return $this->badRequest(self::tooLarge());
        }

        $account = $this->resolveAccount($request, $user);
        if (!$account instanceof Account) {
            return $account;
        }

        $parsed = $this->parser->parse(
            (string) file_get_contents($file->getPathname()),
            $account->getCurrency(),
        );

        if ([] === $parsed['rows']) {
            return $this->badRequest(
                'Aucun mouvement n\'a pu être lu dans ce fichier.',
                $parsed['errors'],
            );
        }

        // Absent means rehearsal: the destructive half of this endpoint is the
        // one that has to be asked for.
        $dryRun = !$request->request->getBoolean('confirm');
        $result = $this->importStatement->execute($account, $parsed['rows'], $dryRun);

        return new JsonResponse([
            'dryRun' => $dryRun,
            'account' => [
                'id' => (string) $account->getId(),
                'name' => $account->getName(),
                'currency' => $account->getCurrency(),
            ],
            'rowsRead' => \count($parsed['rows']),
            'errors' => $parsed['errors'],
        ] + $result);
    }

    /** The account named by the request, or the response explaining why not. */
    private function resolveAccount(Request $request, User $user): Account|JsonResponse
    {
        $wanted = (string) $request->request->get('account', '');
        if ('' === $wanted) {
            return $this->badRequest('Choisissez le compte à alimenter.');
        }

        if (!Ulid::isValid($wanted)) {
            return $this->badRequest("Cet identifiant de compte n'est pas valide.");
        }

        // Scoped to the owner in the query: someone else's account is not
        // findable rather than forbidden, so the answer never confirms that
        // the id exists.
        $account = $this->accountRepository->findOneBy(['id' => new Ulid($wanted), 'user' => $user]);

        if (null === $account) {
            return new JsonResponse(
                ['error' => 'Compte introuvable.'],
                Response::HTTP_NOT_FOUND,
            );
        }

        return $account;
    }

    /**
     * The one refusal for a file too big, whoever noticed first.
     *
     * Read from the ceiling so the sentence cannot drift from the number it
     * announces, and said in Mo because that is what the owner reads on his
     * own file.
     */
    private static function tooLarge(): string
    {
        return \sprintf('Le fichier dépasse la limite de %d Mo.', intdiv(self::MAX_BYTES, 1024 * 1024));
    }

    /** @param list<string> $errors */
    private function badRequest(string $message, array $errors = []): JsonResponse
    {
        return new JsonResponse(
            ['error' => $message, 'errors' => $errors],
            Response::HTTP_BAD_REQUEST,
        );
    }
}
