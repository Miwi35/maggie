<?php

declare(strict_types=1);

namespace Maggie\Core\Command;

use Maggie\Core\Entity\User;
use Maggie\Core\Service\RecetteAccount;
use Maggie\Core\Service\TechnicalAccountTokenIssuer;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Output\OutputInterface;

/**
 * Signs the production Recette account (MAG-249), the one the owner accepts
 * features on. Console only, like `app:smoke:token` (see there for why).
 *
 * The account carries ROLE_PROACTION_TRIGGER, ensured on every run so a revoked
 * permission comes back. stdout carries the token alone, for `$(…)` capture.
 */
#[AsCommand(
    name: 'app:recette:token',
    description: 'Print a JWT for the production Recette technical account',
)]
final class RecetteTokenCommand extends Command
{
    public function __construct(private readonly TechnicalAccountTokenIssuer $issuer)
    {
        parent::__construct();
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $output->writeln($this->issuer->issueToken(
            RecetteAccount::EMAIL,
            RecetteAccount::GOOGLE_ID,
            RecetteAccount::NAME,
            [User::ROLE_PROACTION_TRIGGER],
        ));

        return Command::SUCCESS;
    }
}
