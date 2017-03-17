/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.qualityprofile;

import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import org.apache.commons.lang.StringUtils;
import org.sonar.api.utils.System2;
import org.sonar.core.util.UuidFactory;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.db.qualityprofile.ActiveRuleDto;
import org.sonar.db.qualityprofile.QualityProfileDto;
import org.sonar.server.exceptions.BadRequestException;

import static org.sonar.server.qualityprofile.ActiveRuleChange.Type.DEACTIVATED;
import static org.sonar.server.ws.WsUtils.checkRequest;

/**
 * Create, delete and set as default profile.
 */
public class QProfileFactory {

  private final DbClient db;
  private final UuidFactory uuidFactory;
  private final System2 system2;

  public QProfileFactory(DbClient db, UuidFactory uuidFactory, System2 system2) {
    this.db = db;
    this.uuidFactory = uuidFactory;
    this.system2 = system2;
  }

  // ------------- CREATION

  QualityProfileDto getOrCreate(DbSession dbSession, OrganizationDto organization, QProfileName name) {
    requireNonNull(organization);
    QualityProfileDto profile = db.qualityProfileDao().selectByNameAndLanguage(organization, name.getName(), name.getLanguage(), dbSession);
    if (profile == null) {
      profile = doCreate(dbSession, organization, name, false);
    }
    return profile;
  }

  /**
   * Create the quality profile in DB with the specified name.
   *
   * @throws BadRequestException if a quality profile with the specified name already exists
   */
  public QualityProfileDto checkAndCreate(DbSession dbSession, OrganizationDto organization, QProfileName name) {
    requireNonNull(organization);
    QualityProfileDto dto = db.qualityProfileDao().selectByNameAndLanguage(organization, name.getName(), name.getLanguage(), dbSession);
    checkRequest(dto == null, "Quality profile already exists: %s", name);
    return doCreate(dbSession, organization, name, false);
  }

  /**
   * Create the quality profile in DB with the specified name.
   *
   * A DB error will be thrown if the quality profile already exists.
   */
  public QualityProfileDto create(DbSession dbSession, OrganizationDto organization, QProfileName name, boolean isDefault) {
    return doCreate(dbSession, requireNonNull(organization), name, isDefault);
  }

  private static OrganizationDto requireNonNull(@Nullable OrganizationDto organization) {
    Objects.requireNonNull(organization, "Organization is required, when creating a quality profile.");
    return organization;
  }

  private QualityProfileDto doCreate(DbSession dbSession, OrganizationDto organization, QProfileName name, boolean isDefault) {
    if (StringUtils.isEmpty(name.getName())) {
      throw BadRequestException.create("quality_profiles.profile_name_cant_be_blank");
    }
    Date now = new Date(system2.now());
    QualityProfileDto dto = QualityProfileDto.createFor(uuidFactory.create())
      .setName(name.getName())
      .setOrganizationUuid(organization.getUuid())
      .setLanguage(name.getLanguage())
      .setDefault(isDefault)
      .setRulesUpdatedAtAsDate(now);
    db.qualityProfileDao().insert(dbSession, dto);
    return dto;
  }

  // ------------- DELETION

  /**
   * Session is NOT committed. Profiles marked as "default" for a language can't be deleted,
   * except if the parameter <code>force</code> is true.
   */
  public List<ActiveRuleChange> delete(DbSession session, String key, boolean force) {
    QualityProfileDto profile = db.qualityProfileDao().selectOrFailByKey(session, key);
    List<QualityProfileDto> descendants = db.qualityProfileDao().selectDescendants(session, key);
    if (!force) {
      checkNotDefault(profile);
      for (QualityProfileDto descendant : descendants) {
        checkNotDefault(descendant);
      }
    }
    // delete bottom-up
    List<ActiveRuleChange> changes = new ArrayList<>();
    for (QualityProfileDto descendant : Lists.reverse(descendants)) {
      changes.addAll(doDelete(session, descendant));
    }
    changes.addAll(doDelete(session, profile));
    return changes;
  }

  private List<ActiveRuleChange> doDelete(DbSession session, QualityProfileDto profile) {
    db.qualityProfileDao().deleteAllProjectProfileAssociation(profile.getKey(), session);
    List<ActiveRuleChange> changes = new ArrayList<>();
    for (ActiveRuleDto activeRule : db.activeRuleDao().selectByProfileKey(session, profile.getKey())) {
      db.activeRuleDao().delete(session, activeRule.getKey());
      changes.add(ActiveRuleChange.createFor(DEACTIVATED, activeRule.getKey()));
    }
    db.qualityProfileDao().delete(session, profile.getId());
    return changes;
  }

  // ------------- DEFAULT PROFILE

  private static void checkNotDefault(QualityProfileDto p) {
    if (p.isDefault()) {
      throw BadRequestException.create("The profile marked as default can not be deleted: " + p.getKey());
    }
  }
}
