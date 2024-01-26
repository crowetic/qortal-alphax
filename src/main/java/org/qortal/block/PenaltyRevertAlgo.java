package org.qortal.block;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.persistence.jaxb.JAXBContextFactory;
import org.eclipse.persistence.jaxb.UnmarshallerProperties;
import org.qortal.account.SelfSponsorshipAlgoV1;
import org.qortal.api.model.AccountPenaltyStats;
import org.qortal.crypto.Crypto;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.AccountPenaltyData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.utils.Base58;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.UnmarshalException;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public final class PenaltyRevertAlgo {

	private static final Logger LOGGER = LogManager.getLogger(PenaltyRevertAlgo.class);

	private static final String ACCOUNT_ADD_PENALTY_SOURCE = "pen-old.json";
	private static final List<AccountPenaltyData> accountAddPenalty = addAccountPenalty();

	private static final String ACCOUNT_REMOVE_PENALTY_SOURCE = "pen-new.json";
	private static final List<AccountPenaltyData> accountRemovePenalty = removeAccountPenalty();

	private PenaltyRevertAlgo() {
	}

	@SuppressWarnings("unchecked")
	private static List<AccountPenaltyData> addAccountPenalty() {
		Unmarshaller unmarshaller;

		try {
			// Create JAXB context aware of classes we need to unmarshal
			JAXBContext addpen = JAXBContextFactory.createContext(new Class[] {
					AccountPenaltyData.class
			}, null);

			// Create unmarshaller
			unmarshaller = addpen.createUnmarshaller();

			// Set the unmarshaller media type to JSON
			unmarshaller.setProperty(UnmarshallerProperties.MEDIA_TYPE, "application/json");

			// Tell unmarshaller that there's no JSON root element in the JSON input
			unmarshaller.setProperty(UnmarshallerProperties.JSON_INCLUDE_ROOT, false);
		} catch (JAXBException e) {
			String message = "Failed to setup unmarshaller to add account pemalty";
			LOGGER.error(message, e);
			throw new RuntimeException(message, e);
		}

		ClassLoader classLoader = BlockChain.class.getClassLoader();
		InputStream inAdd = classLoader.getResourceAsStream(ACCOUNT_ADD_PENALTY_SOURCE);
		StreamSource jsonSourceAdd = new StreamSource(inAdd);

		try  {
			// Attempt to unmarshal JSON stream to BlockChain config
			return (List<AccountPenaltyData>) unmarshaller.unmarshal(jsonSourceAdd, AccountPenaltyData.class).getValue();
		} catch (UnmarshalException e) {
			String message = "Failed to parse add account pemalty";
			LOGGER.error(message, e);
			throw new RuntimeException(message, e);
		} catch (JAXBException e) {
			String message = "Unexpected JAXB issue while processing add account penalty";
			LOGGER.error(message, e);
			throw new RuntimeException(message, e);
		}
	}

	private static List<AccountPenaltyData> removeAccountPenalty() {
		Unmarshaller unmarshaller;

		try {
			// Create JAXB context aware of classes we need to unmarshal
			JAXBContext removepen = JAXBContextFactory.createContext(new Class[] {
					AccountPenaltyData.class
			}, null);

			// Create unmarshaller
			unmarshaller = removepen.createUnmarshaller();

			// Set the unmarshaller media type to JSON
			unmarshaller.setProperty(UnmarshallerProperties.MEDIA_TYPE, "application/json");

			// Tell unmarshaller that there's no JSON root element in the JSON input
			unmarshaller.setProperty(UnmarshallerProperties.JSON_INCLUDE_ROOT, false);
		} catch (JAXBException e) {
			String message = "Failed to setup unmarshaller to remove account pemalty";
			LOGGER.error(message, e);
			throw new RuntimeException(message, e);
		}

		ClassLoader classLoader = BlockChain.class.getClassLoader();
		InputStream inRemove = classLoader.getResourceAsStream(ACCOUNT_REMOVE_PENALTY_SOURCE);
		StreamSource jsonSourceRemove = new StreamSource(inRemove);

		try  {
			// Attempt to unmarshal JSON stream to BlockChain config
			return (List<AccountPenaltyData>) unmarshaller.unmarshal(jsonSourceRemove, AccountPenaltyData.class).getValue();
		} catch (UnmarshalException e) {
			String message = "Failed to parse remove account pemalty";
			LOGGER.error(message, e);
			throw new RuntimeException(message, e);
		} catch (JAXBException e) {
			String message = "Unexpected JAXB issue while processing remove account penalty";
			LOGGER.error(message, e);
			throw new RuntimeException(message, e);
		}
	}

	public static void addAccountPenalties(Block block) throws DataException {
		LOGGER.info("Running algo for add penalties - this will take a while...");
		logPenaltyStats(block.repository);
		long startTime = System.currentTimeMillis();
		Set<AccountPenaltyData> penalties = new HashSet<AccountPenaltyData>(accountAddPenalty);
		block.repository.getAccountRepository().updateBlocksMintedPenalties(penalties);
		long totalTime = System.currentTimeMillis() - startTime;
		String hash = getHash(penalties.stream().map(p -> p.getAddress()).collect(Collectors.toList()));
		LOGGER.debug("{} penalty addresses processed (hash: {}). Total time taken: {} seconds", penalties.size(), hash, (int)(totalTime / 1000.0f));
		logPenaltyStats(block.repository);
		int updatedCount = updateAccountLevels(block.repository, penalties);
		LOGGER.debug("Account levels updated for {} penalty addresses", updatedCount);
	}

	public static void removeAccountPenalties(Block block) throws DataException {
		LOGGER.info("Running algo for remove penalties - this will take a while...");
		logPenaltyStats(block.repository);
		long startTime = System.currentTimeMillis();
		Set<AccountPenaltyData> penalties = new HashSet<AccountPenaltyData>(accountRemovePenalty);
		block.repository.getAccountRepository().updateBlocksMintedPenalties(penalties);
		long totalTime = System.currentTimeMillis() - startTime;
		String hash = getHash(penalties.stream().map(p -> p.getAddress()).collect(Collectors.toList()));
		LOGGER.debug("{} penalty addresses processed (hash: {}). Total time taken: {} seconds", penalties.size(), hash, (int)(totalTime / 1000.0f));
		logPenaltyStats(block.repository);
		int updatedCount = updateAccountLevels(block.repository, penalties);
		LOGGER.debug("Account levels updated for {} penalty addresses", updatedCount);
	}

	private static int updateAccountLevels(Repository repository, Set<AccountPenaltyData> accountPenalties) throws DataException {
		final List<Integer> cumulativeBlocksByLevel = BlockChain.getInstance().getCumulativeBlocksByLevel();
		final int maximumLevel = cumulativeBlocksByLevel.size() - 1;

		int updatedCount = 0;

		for (AccountPenaltyData penaltyData : accountPenalties) {
			AccountData accountData = repository.getAccountRepository().getAccount(penaltyData.getAddress());
			final int effectiveBlocksMinted = accountData.getBlocksMinted() + accountData.getBlocksMintedAdjustment() + accountData.getBlocksMintedPenalty();

			// Shortcut for penalties
			if (effectiveBlocksMinted < 0) {
				accountData.setLevel(0);
				repository.getAccountRepository().setLevel(accountData);
				updatedCount++;
				LOGGER.debug(() -> String.format("Block minter %s dropped to level %d", accountData.getAddress(), accountData.getLevel()));
				continue;
			}

			for (int newLevel = maximumLevel; newLevel >= 0; --newLevel) {
				if (effectiveBlocksMinted >= cumulativeBlocksByLevel.get(newLevel)) {
					accountData.setLevel(newLevel);
					repository.getAccountRepository().setLevel(accountData);
					updatedCount++;
					LOGGER.debug(() -> String.format("Block minter %s increased to level %d", accountData.getAddress(), accountData.getLevel()));
					break;
				}
			}
		}
		return updatedCount;
	}

	private static void logPenaltyStats(Repository repository) {
		try {
			LOGGER.info(getPenaltyStats(repository));
		} catch (DataException e) {}
	}

	private static AccountPenaltyStats getPenaltyStats(Repository repository) throws DataException {
		List<AccountData> accounts = repository.getAccountRepository().getPenaltyAccounts();
		return AccountPenaltyStats.fromAccounts(accounts);
	}

	public static String getHash(List<String> penaltyAddresses) {
		if (penaltyAddresses == null || penaltyAddresses.isEmpty()) {
			return null;
		}
		Collections.sort(penaltyAddresses);
		return Base58.encode(Crypto.digest(StringUtils.join(penaltyAddresses).getBytes(StandardCharsets.UTF_8)));
	}
}