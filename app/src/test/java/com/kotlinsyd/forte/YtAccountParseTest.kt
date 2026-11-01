package com.kotlinsyd.forte

import com.kotlinsyd.forte.data.YtAccount
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parse checks for the v1.0.12 account-identity chain
 * ([YtAccount.parseIdentity] + [YtAccount.mergeAccountInfo]).
 *
 * The fixtures are trimmed copies of the real response shapes:
 *  - the account_menu envelope (actions → openPopupAction → popup →
 *    multiPageMenuRenderer → header → activeAccountHeaderRenderer),
 *    as the account menu response carries it;
 *  - the switcher envelope (data → actions →
 *    getMultiPageMenuAction → menu → sections → accountItem rows)
 *    shared by account/accounts_list and
 *    getAccountSwitcherEndpoint.
 * No fixture is a recording of a real account; names/photos are
 * invented, the shapes are the real ones.
 */
class YtAccountParseTest {

    private fun parse(json: String) = YtAccount.parseIdentity(JSONObject(json))

    @Test
    fun `menu header with channel yields name handle and photo`() {
        val info = parse(MENU_WITH_CHANNEL)!!
        assertEquals("Ada Lovelace", info.name)
        assertEquals("@ada", info.handle)
        // The largest thumbnail is the one the UI should show.
        assertEquals("https://img.example/ada=s176", info.avatarUrl)
    }

    @Test
    fun `no-channel accounts list item yields name and photo, no handle`() {
        // THE owner's case: a normal Google account with no YouTube
        // channel. No header renderer anywhere, no channelHandle —
        // one selected personal accountItem carries name + photo.
        val info = parse(ACCOUNTS_LIST_NO_CHANNEL)!!
        assertEquals("Ada Lovelace", info.name)
        assertEquals("https://img.example/ada=s176", info.avatarUrl)
        assertNull(info.handle)
    }

    @Test
    fun `selected switcher item wins over an unselected brand item listed first`() {
        val info = parse(SWITCHER_TWO_ITEMS)!!
        assertEquals("Ada Lovelace", info.name)
        assertEquals("https://img.example/ada=s40", info.avatarUrl)
        assertEquals("@ada", info.handle)
    }

    @Test
    fun `fields are independent within one response - name from header, photo from item`() {
        val info = parse(MENU_NAME_ONLY_PLUS_ITEM_PHOTO)!!
        assertEquals("Ada Lovelace", info.name)
        assertEquals("https://img.example/ada=s176", info.avatarUrl)
    }

    @Test
    fun `name falls back to the header email when no name is carried`() {
        val info = parse(MENU_EMAIL_ONLY)!!
        assertEquals("ada@example.com", info.name)
        assertEquals("https://img.example/ada=s176", info.avatarUrl)
    }

    @Test
    fun `chain merge combines name from one source with photo from another`() {
        val fromMenu = parse(MENU_NAME_ONLY_PLUS_ITEM_PHOTO)!!
        // A menu response that proved the name only…
        val nameOnly = YtAccount.AccountInfo(fromMenu.name, fromMenu.handle, null)
        // …and a list response that proved the photo only.
        val photoOnly = parse(LIST_PHOTO_ONLY)!!
        assertNull(photoOnly.name)
        val merged = YtAccount.mergeAccountInfo(nameOnly, photoOnly)!!
        assertEquals("Ada Lovelace", merged.name)
        assertEquals("https://img.example/ada=s176", merged.avatarUrl)
        // A field already proven is never replaced by a later source.
        val stillAda = YtAccount.mergeAccountInfo(merged, fromMenu)!!
        assertEquals("Ada Lovelace", stillAda.name)
        assertEquals("https://img.example/ada=s176", stillAda.avatarUrl)
    }

    @Test
    fun `an envelope with no identity yields null, not a blank identity`() {
        assertNull(parse("""{"responseContext":{},"contents":{}}"""))
    }

    // ------------------------------------------------------------------
    // v1.0.13: the session scope — the step the identity chain was
    // missing. The shell fixtures are trimmed page-config shapes
    // (key order and values invented, keys and forms are the real
    // ones the page config carries).
    // ------------------------------------------------------------------

    @Test
    fun `datasync id normalises to the delegated half`() {
        assertEquals("SESSION_B", YtAccount.normalizeDataSyncId("SYNC_A||SESSION_B"))
        assertEquals("SYNC_A", YtAccount.normalizeDataSyncId("SYNC_A||"))
        assertEquals("SYNC_A", YtAccount.normalizeDataSyncId("SYNC_A"))
        assertNull(YtAccount.normalizeDataSyncId(""))
        assertNull(YtAccount.normalizeDataSyncId("  "))
        assertNull(YtAccount.normalizeDataSyncId(null))
    }

    @Test
    fun `shell scope reads the page's own index and identity`() {
        val scope = YtAccount.scopeFromShell(SHELL_SIGNED_IN)
        // The page says this session acts as jar entry 2 — not 0.
        assertEquals("2", scope.authUser)
        // onBehalfOfUser is the delegated half of the DATASYNC_ID.
        assertEquals("Uj789xyz", scope.dataSyncId)
        assertNull(scope.pageId)
        assertEquals("1.20261031.09.00", scope.clientVersion)
        assertEquals("Cgtrandomvisitor", YtAccount.shellVisitorData(SHELL_SIGNED_IN))
    }

    @Test
    fun `shell scope prefers a delegated page id over the datasync id`() {
        val scope = YtAccount.scopeFromShell(SHELL_BRAND)
        assertEquals("113355", scope.pageId)
        assertEquals("113355", scope.dataSyncId)
        assertEquals("0", scope.authUser)
    }

    @Test
    fun `signed-out shell contributes no identity, only its version`() {
        val scope = YtAccount.scopeFromShell(SHELL_SIGNED_OUT)
        assertEquals("0", scope.authUser)
        assertNull(scope.dataSyncId)
        assertNull(scope.pageId)
        assertEquals("1.20261031.09.00", scope.clientVersion)
    }

    // ------------------------------------------------------------------
    // v1.0.15: the auth shape — which cookie feeds the hash, the
    // retry ladder, and the avatar size upgrade. (The seat-jar
    // choice itself reads Android's CookieManager and cannot run
    // on the JVM; what CAN be pinned here is the pick order over
    // whatever jar the seat produced.)
    // ------------------------------------------------------------------

    @Test
    fun `sapisid entry follows the lineage pick order and names the variant`() {
        val all = mapOf(
            "SAPISID" to "plain",
            "__Secure-3PAPISID" to "three",
            "__Secure-1PAPISID" to "one",
        )
        assertEquals("SAPISID" to "plain", YtAccount.sapisidEntry(all))
        assertEquals(
            "3PAPISID" to "three",
            YtAccount.sapisidEntry(all - "SAPISID"),
        )
        assertEquals(
            "1PAPISID" to "one",
            YtAccount.sapisidEntry(all - "SAPISID" - "__Secure-3PAPISID"),
        )
        assertNull(YtAccount.sapisidEntry(mapOf("SID" to "x")))
        assertNull(YtAccount.sapisidEntry(emptyMap()))
    }

    @Test
    fun `identity ladder starts scoped, then drops the claim, then the index`() {
        // Scope with a claim and a non-zero index: all three rungs.
        assertEquals(
            listOf(true to null, false to null, false to "0"),
            YtAccount.identityShapeLadder(hasUserClaim = true, authUser = "2"),
        )
        // Claim but already index 0: the plain rung would repeat
        // the no-claim rung's headers only in body — still distinct.
        assertEquals(
            listOf(true to null, false to null),
            YtAccount.identityShapeLadder(hasUserClaim = true, authUser = "0"),
        )
        // No claim, non-zero index: scoped, then plain.
        assertEquals(
            listOf(true to null, false to "0"),
            YtAccount.identityShapeLadder(hasUserClaim = false, authUser = "3"),
        )
        // No claim, index 0: the first shape already IS the plain
        // one — a single rung, no pointless retries.
        assertEquals(
            listOf(true to null),
            YtAccount.identityShapeLadder(hasUserClaim = false, authUser = "0"),
        )
    }

    @Test
    fun `bigger avatar upgrades only the size segment`() {
        assertEquals(
            "https://lh3.example/a/photo=s176-c",
            YtAccount.biggerAvatar("https://lh3.example/a/photo=s32-c"),
        )
        assertEquals(
            "https://lh3.example/a/photo=s176",
            YtAccount.biggerAvatar("https://lh3.example/a/photo=s176"),
        )
        // No size segment: untouched.
        assertEquals(
            "https://img.example/ada",
            YtAccount.biggerAvatar("https://img.example/ada"),
        )
        assertNull(YtAccount.biggerAvatar(null))
        assertNull(YtAccount.biggerAvatar("  "))
    }

    private companion object {
        const val MENU_WITH_CHANNEL = """
{
 "actions": [
  {
   "openPopupAction": {
    "popup": {
     "multiPageMenuRenderer": {
      "header": {
       "activeAccountHeaderRenderer": {
        "accountName": {
         "runs": [
          {
           "text": "Ada Lovelace"
          }
         ]
        },
        "email": {
         "runs": [
          {
           "text": "ada@example.com"
          }
         ]
        },
        "channelHandle": {
         "runs": [
          {
           "text": "@ada"
          }
         ]
        },
        "accountPhoto": {
         "thumbnails": [
          {
           "url": "https://img.example/ada=s40",
           "width": 40
          },
          {
           "url": "https://img.example/ada=s176",
           "width": 176
          }
         ]
        }
       }
      }
     }
    }
   }
  }
 ]
}
        """

        const val ACCOUNTS_LIST_NO_CHANNEL = """
{
 "data": {
  "actions": [
   {
    "getMultiPageMenuAction": {
     "menu": {
      "multiPageMenuRenderer": {
       "sections": [
        {
         "accountSectionListRenderer": {
          "contents": [
           {
            "accountItemSectionRenderer": {
             "contents": [
              {
               "accountItem": {
                "accountName": {
                 "simpleText": "Ada Lovelace"
                },
                "accountPhoto": {
                 "thumbnails": [
                  {
                   "url": "https://img.example/ada=s32",
                   "width": 32
                  },
                  {
                   "url": "https://img.example/ada=s176",
                   "width": 176
                  }
                 ]
                },
                "isSelected": true,
                "accountByline": {
                 "simpleText": "ada@example.com"
                },
                "serviceEndpoint": {
                 "selectActiveIdentityEndpoint": {
                  "supportedTokens": [
                   {
                    "accountSigninToken": {
                     "signinUrl": "https://accounts.google.com/x"
                    }
                   },
                   {
                    "datasyncIdToken": {
                     "datasyncIdToken": "SYNC_PERSONAL||SESSION_A"
                    }
                   }
                  ]
                 }
                }
               }
              }
             ]
            }
           }
          ]
         }
        }
       ]
      }
     }
    }
   }
  ]
 }
}
        """

        const val SWITCHER_TWO_ITEMS = """
{
 "data": {
  "actions": [
   {
    "getMultiPageMenuAction": {
     "menu": {
      "multiPageMenuRenderer": {
       "sections": [
        {
         "accountSectionListRenderer": {
          "contents": [
           {
            "accountItemSectionRenderer": {
             "contents": [
              {
               "accountItem": {
                "accountName": {
                 "runs": [
                  {
                   "text": "Analytical Engine Radio"
                  }
                 ]
                },
                "accountPhoto": {
                 "thumbnails": [
                  {
                   "url": "https://img.example/brand=s40",
                   "width": 40
                  }
                 ]
                },
                "isSelected": false,
                "accountByline": {
                 "simpleText": "Brand account"
                },
                "serviceEndpoint": {
                 "selectActiveIdentityEndpoint": {
                  "supportedTokens": [
                   {
                    "pageIdToken": {
                     "pageId": "113355"
                    }
                   },
                   {
                    "datasyncIdToken": {
                     "datasyncIdToken": "SYNC_BRAND||SESSION_B"
                    }
                   }
                  ]
                 }
                }
               }
              },
              {
               "accountItem": {
                "accountName": {
                 "simpleText": "Ada Lovelace"
                },
                "accountPhoto": {
                 "thumbnails": [
                  {
                   "url": "https://img.example/ada=s40",
                   "width": 40
                  }
                 ]
                },
                "isSelected": true,
                "channelHandle": {
                 "simpleText": "@ada"
                },
                "serviceEndpoint": {
                 "selectActiveIdentityEndpoint": {
                  "supportedTokens": [
                   {
                    "accountSigninToken": {
                     "signinUrl": "https://accounts.google.com/x"
                    }
                   },
                   {
                    "datasyncIdToken": {
                     "datasyncIdToken": "SYNC_PERSONAL||SESSION_A"
                    }
                   }
                  ]
                 }
                }
               }
              }
             ]
            }
           }
          ]
         }
        }
       ]
      }
     }
    }
   }
  ]
 }
}
        """

        const val MENU_NAME_ONLY_PLUS_ITEM_PHOTO = """
{
 "actions": [
  {
   "openPopupAction": {
    "popup": {
     "multiPageMenuRenderer": {
      "header": {
       "activeAccountHeaderRenderer": {
        "accountName": {
         "runs": [
          {
           "text": "Ada Lovelace"
          }
         ]
        },
        "email": {
         "runs": [
          {
           "text": "ada@example.com"
          }
         ]
        }
       }
      },
      "sections": [
       {
        "accountSectionListRenderer": {
         "contents": [
          {
           "accountItemSectionRenderer": {
            "contents": [
             {
              "accountItem": {
               "accountPhoto": {
                "thumbnails": [
                 {
                  "url": "https://img.example/ada=s176",
                  "width": 176
                 }
                ]
               },
               "isSelected": true
              }
             }
            ]
           }
          }
         ]
        }
       }
      ]
     }
    }
   }
  }
 ]
}
        """

        const val MENU_EMAIL_ONLY = """
{
 "actions": [
  {
   "openPopupAction": {
    "popup": {
     "multiPageMenuRenderer": {
      "header": {
       "activeAccountHeaderRenderer": {
        "email": {
         "runs": [
          {
           "text": "ada@example.com"
          }
         ]
        },
        "accountPhoto": {
         "thumbnails": [
          {
           "url": "https://img.example/ada=s176",
           "width": 176
          }
         ]
        }
       }
      }
     }
    }
   }
  }
 ]
}
        """

        const val LIST_PHOTO_ONLY = """
{
 "contents": [
  {
   "accountItem": {
    "accountPhoto": {
     "thumbnails": [
      {
       "url": "https://img.example/ada=s176",
       "width": 176
      }
     ]
    },
    "isSelected": true
   }
  }
 ]
}
        """

        const val SHELL_SIGNED_IN = """
<html><head><script>ytcfg.set({"DATASYNC_ID":"Uj123abc||Uj789xyz","DELEGATED_SESSION_ID":"","INNERTUBE_CLIENT_VERSION":"1.20261031.09.00","LOGGED_IN":true,"SESSION_INDEX":2,"VISITOR_DATA":"Cgtrandomvisitor"});</script></head><body></body></html>
        """

        const val SHELL_BRAND = """
<html><head><script>ytcfg.set({"DATASYNC_ID":"Uj123abc||","DELEGATED_SESSION_ID":"113355","INNERTUBE_CLIENT_VERSION":"1.20261031.09.00","LOGGED_IN":true,"SESSION_INDEX":"0","VISITOR_DATA":"Cgtrandomvisitor"});</script></head><body></body></html>
        """

        const val SHELL_SIGNED_OUT = """
<html><head><script>ytcfg.set({"DATASYNC_ID":"Ujnobody||","INNERTUBE_CLIENT_VERSION":"1.20261031.09.00","LOGGED_IN":false,"SESSION_INDEX":0,"VISITOR_DATA":"Cgtrandomvisitor"});</script></head><body></body></html>
        """
    }
}
