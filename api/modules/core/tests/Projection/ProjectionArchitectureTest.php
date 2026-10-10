<?php

namespace Maggie\Core\Tests\Projection;

use PHPUnit\Framework\TestCase;

/**
 * Publishing on Mercure and indexing belong to the {@see \Maggie\Core\Projection\ProjectionMiddleware}
 * (agent-os/standards/backend/projection.md). Code that still does it by hand is listed here and the
 * list only shrinks: a file migrated to the middleware must leave it, a new file can never join it.
 */
class ProjectionArchitectureTest extends TestCase
{
    private const PRIMITIVES = [
        'Symfony\Component\Mercure\HubInterface',
        'Maggie\Core\Mercure\EntityBroadcaster',
        'Maggie\Core\Elasticsearch\Message\IndexDocumentCommand',
    ];

    /** The projection itself and the code that defines or consumes its messages. */
    private const OWNERS = [
        'modules/core/src/Projection/ProjectionMiddleware.php',
        'modules/core/src/Elasticsearch/Message/IndexDocumentCommand.php',
        'modules/core/src/Elasticsearch/MessageHandler/IndexDocumentHandler.php',
        'modules/core/src/Mercure/EntityBroadcaster.php',
    ];

    /** Manual projection still to be replaced by the middleware (commands, imports and syncs outside the CRUD flow). */
    private const TO_MIGRATE = [
        'modules/calendar/src/Command/DedupeGoogleAgendasCommand.php',
        'modules/calendar/src/Service/GoogleCalendarSyncService.php',
        'modules/calendar/src/Service/GoogleTaskListSelection.php',
        'modules/calendar/src/Service/GoogleTasksSyncService.php',
        'modules/cookbook/src/Command/FileMealsInModuleAgendaCommand.php',
        'modules/cookbook/src/MessageHandler/DeleteRecipeHandler.php',
        'modules/core/src/Command/AbstractUserRoleCommand.php',
        'modules/core/src/Service/TechnicalAccountTokenIssuer.php',
        'modules/finance/src/Command/BackfillCounterpartyCommand.php',
        'modules/finance/src/UseCase/CompleteBankAuthorization.php',
        'modules/finance/src/UseCase/ImportStatement.php',
        'modules/finance/src/UseCase/InstallStandardCategories.php',
        'modules/finance/src/UseCase/MergeDuplicateAccounts.php',
        'modules/finance/src/UseCase/RepairAccountCurrencies.php',
        'modules/finance/src/UseCase/SuggestCategorizationRules.php',
        'modules/finance/src/UseCase/SyncBankAccounts.php',
    ];

    public function testOnlyTheProjectionAndTheListedCodeProjectByHand(): void
    {
        $api = \dirname(__DIR__, 4);
        $users = [];

        $files = new \RecursiveIteratorIterator(new \RecursiveDirectoryIterator($api.'/modules', \FilesystemIterator::SKIP_DOTS));
        foreach ($files as $file) {
            $path = str_replace($api.'/', '', $file->getPathname());
            if ('php' !== $file->getExtension() || !preg_match('#^modules/[^/]+/src/#', $path)) {
                continue;
            }

            $source = (string) file_get_contents($file->getPathname());
            foreach (self::PRIMITIVES as $primitive) {
                if (preg_match('/^use '.preg_quote($primitive, '/').'\s*;/m', $source)) {
                    $users[] = $path;
                    break;
                }
            }
        }

        $users = array_values(array_diff($users, self::OWNERS));
        sort($users);
        $expected = self::TO_MIGRATE;
        sort($expected);

        self::assertSame(
            $expected,
            $users,
            'Publish and index through ProjectionMiddleware (nothing to call). If a file in this list no longer '
            .'uses HubInterface, EntityBroadcaster or IndexDocumentCommand, remove it from TO_MIGRATE.',
        );
    }
}
