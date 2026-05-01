package com.example.galaxywatch.presentation;

public class BpPredictorDBP {
    public static double score(double[] input) {
        double var0;
        if (input[1] < 0.8135) {
            if (input[2] < 8460.779) {
                if (input[1] < 0.78113335) {
                    var0 = -0.08218979;
                } else {
                    var0 = 0.7337498;
                }
            } else {
                var0 = 1.3531249;
            }
        } else {
            if (input[2] < 2285.0303) {
                if (input[2] < 162.14632) {
                    var0 = 0.0020832063;
                } else {
                    var0 = 0.30277762;
                }
            } else {
                if (input[0] < 70.12564) {
                    var0 = -0.047916796;
                } else {
                    var0 = -1.339286;
                }
            }
        }
        double var1;
        if (input[2] < 8460.779) {
            if (input[1] < 0.8135) {
                if (input[1] < 0.77646667) {
                    var1 = -0.08186215;
                } else {
                    var1 = 0.6354099;
                }
            } else {
                if (input[2] < 2285.0303) {
                    var1 = 0.21293373;
                } else {
                    var1 = -1.0828089;
                }
            }
        } else {
            var1 = 1.2516403;
        }
        double var2;
        if (input[2] < 8460.779) {
            if (input[1] < 0.8135) {
                if (input[1] < 0.7656129) {
                    var2 = -0.10161445;
                } else {
                    var2 = 0.48909268;
                }
            } else {
                if (input[2] < 2285.0303) {
                    var2 = 0.1969635;
                } else {
                    var2 = -0.98806304;
                }
            }
        } else {
            var2 = 1.1577673;
        }
        double var3;
        if (input[2] < 8460.779) {
            if (input[1] < 0.8135) {
                if (input[1] < 0.78113335) {
                    var3 = -0.06334128;
                } else {
                    var3 = 0.56650764;
                }
            } else {
                if (input[2] < 2285.0303) {
                    var3 = 0.18219148;
                } else {
                    var3 = -0.9016078;
                }
            }
        } else {
            var3 = 1.0709347;
        }
        double var4;
        if (input[2] < 8460.779) {
            if (input[1] < 0.8135) {
                if (input[1] < 0.7656129) {
                    var4 = -0.08547753;
                } else {
                    var4 = 0.41156527;
                }
            } else {
                if (input[2] < 2285.0303) {
                    var4 = 0.16852723;
                } else {
                    var4 = -0.82271683;
                }
            }
        } else {
            var4 = 0.99061435;
        }
        double var5;
        if (input[2] < 8460.779) {
            if (input[1] < 0.8135) {
                if (input[2] < 6683.381) {
                    var5 = 0.096453056;
                } else {
                    var5 = -0.67027897;
                }
            } else {
                if (input[2] < 2285.0303) {
                    var5 = 0.1558878;
                } else {
                    var5 = -0.7507292;
                }
            }
        } else {
            var5 = 0.9163181;
        }
        double var6;
        if (input[2] < 8460.779) {
            if (input[1] < 0.8135) {
                if (input[1] < 0.78113335) {
                    var6 = -0.05856998;
                } else {
                    var6 = 0.47746783;
                }
            } else {
                if (input[2] < 2285.0303) {
                    var6 = 0.14419594;
                } else {
                    var6 = -0.6850401;
                }
            }
        } else {
            var6 = 0.8475941;
        }
        double var7;
        if (input[2] < 8460.779) {
            if (input[2] < 7093.6333) {
                if (input[1] < 0.8135) {
                    var7 = 0.08507497;
                } else {
                    var7 = -0.40611503;
                }
            } else {
                if (input[0] < 82.58603) {
                    var7 = -0.8329897;
                } else {
                    var7 = 0.10925026;
                }
            }
        } else {
            if (input[0] < 82.6964) {
                var7 = 0.8969106;
            } else {
                var7 = 0.22268295;
            }
        }
        double var8;
        if (input[2] < 8460.779) {
            if (input[2] < 7093.6333) {
                if (input[1] < 0.8135) {
                    var8 = 0.07671935;
                } else {
                    var8 = -0.3691957;
                }
            } else {
                if (input[0] < 82.58603) {
                    var8 = -0.77051526;
                } else {
                    var8 = 0.103787616;
                }
            }
        } else {
            if (input[0] < 82.6964) {
                var8 = 0.83711654;
            } else {
                var8 = 0.21154861;
            }
        }
        double var9;
        if (input[2] < 8460.779) {
            if (input[2] < 7093.6333) {
                if (input[1] < 0.8135) {
                    var9 = 0.069184236;
                } else {
                    var9 = -0.33563262;
                }
            } else {
                if (input[0] < 82.58603) {
                    var9 = -0.7127266;
                } else {
                    var9 = 0.0985981;
                }
            }
        } else {
            if (input[0] < 82.6964) {
                var9 = 0.78130853;
            } else {
                var9 = 0.20097123;
            }
        }
        double var10;
        if (input[2] < 8460.779) {
            if (input[0] < 97.74436) {
                if (input[1] < 0.7656129) {
                    var10 = -0.22005673;
                } else {
                    var10 = 0.08179621;
                }
            } else {
                if (input[2] < 5279.9033) {
                    var10 = 0.15050195;
                } else {
                    var10 = 0.6593647;
                }
            }
        } else {
            if (input[0] < 80.0901) {
                var10 = 0.84691584;
            } else {
                var10 = 0.29189226;
            }
        }
        double var11;
        if (input[2] < 8460.779) {
            if (input[2] < 7093.6333) {
                if (input[1] < 0.8135) {
                    var11 = 0.06868451;
                } else {
                    var11 = -0.31255648;
                }
            } else {
                if (input[0] < 82.58603) {
                    var11 = -0.65786076;
                } else {
                    var11 = 0.1046711;
                }
            }
        } else {
            if (input[0] < 80.0901) {
                var11 = 0.80457;
            } else {
                var11 = 0.2724327;
            }
        }
        double var12;
        if (input[2] < 8460.779) {
            if (input[2] < 7093.6333) {
                if (input[1] < 0.8135) {
                    var12 = 0.061938424;
                } else {
                    var12 = -0.28414252;
                }
            } else {
                if (input[0] < 82.58603) {
                    var12 = -0.6085213;
                } else {
                    var12 = 0.09943771;
                }
            }
        } else {
            if (input[0] < 80.0901) {
                var12 = 0.76434135;
            } else {
                var12 = 0.25427067;
            }
        }
        double var13;
        if (input[2] < 8460.779) {
            if (input[0] < 97.74436) {
                if (input[1] < 0.7656129) {
                    var13 = -0.20748387;
                } else {
                    var13 = 0.10167285;
                }
            } else {
                if (input[2] < 5279.9033) {
                    var13 = 0.12473189;
                } else {
                    var13 = 0.6198654;
                }
            }
        } else {
            if (input[0] < 80.0901) {
                var13 = 0.7261242;
            } else {
                var13 = 0.23731919;
            }
        }
        double var14;
        if (input[2] < 8460.779) {
            if (input[2] < 7093.6333) {
                if (input[1] < 0.8135) {
                    var14 = 0.061605413;
                } else {
                    var14 = -0.26755413;
                }
            } else {
                if (input[0] < 82.58603) {
                    var14 = -0.56277865;
                } else {
                    var14 = 0.104839705;
                }
            }
        } else {
            if (input[0] < 80.0901) {
                var14 = 0.68981785;
            } else {
                var14 = 0.22149785;
            }
        }
        double var15;
        if (input[2] < 8460.779) {
            if (input[1] < 0.5710732) {
                if (input[1] < 0.5183778) {
                    var15 = -0.16400872;
                } else {
                    var15 = 0.31293488;
                }
            } else {
                if (input[1] < 0.7656129) {
                    var15 = -0.16386901;
                } else {
                    var15 = 0.104159914;
                }
            }
        } else {
            if (input[0] < 80.0901) {
                var15 = 0.65532684;
            } else {
                var15 = 0.20673142;
            }
        }
        double var16;
        if (input[1] < 0.80596554) {
            if (input[1] < 0.78113335) {
                if (input[2] < 1902.6943) {
                    var16 = -0.3890096;
                } else {
                    var16 = 0.057583004;
                }
            } else {
                if (input[2] < 5861.589) {
                    var16 = 0.76004946;
                } else {
                    var16 = -0.053970337;
                }
            }
        } else {
            if (input[0] < 72.56652) {
                if (input[2] < 2850.1316) {
                    var16 = -0.16670762;
                } else {
                    var16 = 0.76429063;
                }
            } else {
                if (input[2] < 2285.0303) {
                    var16 = 0.31828767;
                } else {
                    var16 = -0.66657525;
                }
            }
        }
        double var17;
        if (input[2] < 10006.233) {
            if (input[0] < 87.60951) {
                if (input[1] < 0.7656129) {
                    var17 = -0.28192672;
                } else {
                    var17 = 0.09130115;
                }
            } else {
                if (input[1] < 0.6358108) {
                    var17 = 0.0034435473;
                } else {
                    var17 = 0.35185987;
                }
            }
        } else {
            var17 = 0.52634025;
        }
        double var18;
        if (input[2] < 8230.291) {
            if (input[2] < 7093.6333) {
                if (input[1] < 0.80596554) {
                    var18 = 0.06438996;
                } else {
                    var18 = -0.21845153;
                }
            } else {
                if (input[0] < 75.17227) {
                    var18 = -0.008440018;
                } else {
                    var18 = -0.6682572;
                }
            }
        } else {
            if (input[0] < 80.0901) {
                var18 = 0.59336436;
            } else {
                var18 = 0.18809223;
            }
        }
        double var19;
        if (input[2] < 8230.291) {
            if (input[2] < 7093.6333) {
                if (input[2] < 5146.7627) {
                    var19 = -0.050458025;
                } else {
                    var19 = 0.21212469;
                }
            } else {
                if (input[0] < 75.17227) {
                    var19 = -0.008018113;
                } else {
                    var19 = -0.6237066;
                }
            }
        } else {
            if (input[0] < 80.0901) {
                var19 = 0.5636963;
            } else {
                var19 = 0.17398511;
            }
        }
        double var20;
        if (input[1] < 0.8135) {
            if (input[1] < 0.7656129) {
                if (input[1] < 0.7552903) {
                    var20 = 0.019167198;
                } else {
                    var20 = -0.9074077;
                }
            } else {
                if (input[0] < 77.645584) {
                    var20 = 0.09798478;
                } else {
                    var20 = 1.1416532;
                }
            }
        } else {
            if (input[2] < 2285.0303) {
                if (input[2] < 162.14632) {
                    var20 = 0.050538637;
                } else {
                    var20 = 0.3311717;
                }
            } else {
                if (input[0] < 70.12564) {
                    var20 = 0.25794297;
                } else {
                    var20 = -0.5737569;
                }
            }
        }
        double var21;
        if (input[2] < 8460.779) {
            if (input[0] < 97.74436) {
                if (input[1] < 0.7656129) {
                    var21 = -0.16086082;
                } else {
                    var21 = 0.08776801;
                }
            } else {
                if (input[2] < 3415.865) {
                    var21 = 0.349403;
                } else {
                    var21 = 0.013632774;
                }
            }
        } else {
            if (input[0] < 80.0901) {
                var21 = 0.5345532;
            } else {
                var21 = 0.15554658;
            }
        }
        double var22;
        if (input[1] < 0.80596554) {
            if (input[1] < 0.78113335) {
                if (input[0] < 80.08676) {
                    var22 = -0.35501596;
                } else {
                    var22 = 0.046200775;
                }
            } else {
                if (input[2] < 5861.589) {
                    var22 = 0.6419394;
                } else {
                    var22 = -0.0789505;
                }
            }
        } else {
            if (input[0] < 72.58771) {
                if (input[1] < 0.8135) {
                    var22 = 0.80740815;
                } else {
                    var22 = -0.09401017;
                }
            } else {
                if (input[0] < 75.059425) {
                    var22 = -0.99304384;
                } else {
                    var22 = -0.09903412;
                }
            }
        }
        double var23;
        if (input[2] < 8230.291) {
            if (input[2] < 7093.6333) {
                if (input[2] < 5146.7627) {
                    var23 = -0.04246116;
                } else {
                    var23 = 0.18566468;
                }
            } else {
                if (input[0] < 75.17227) {
                    var23 = -0.011952973;
                } else {
                    var23 = -0.5733012;
                }
            }
        } else {
            if (input[0] < 80.0901) {
                var23 = 0.5055153;
            } else {
                var23 = 0.15227738;
            }
        }
        double var24;
        if (input[1] < 0.8323214) {
            if (input[0] < 72.56652) {
                var24 = 0.76916045;
            } else {
                if (input[1] < 0.80596554) {
                    var24 = 0.045674466;
                } else {
                    var24 = -0.297961;
                }
            }
        } else {
            if (input[0] < 72.41551) {
                if (input[0] < 70.12564) {
                    var24 = 0.2716367;
                } else {
                    var24 = 0.0504467;
                }
            } else {
                var24 = -0.66982883;
            }
        }
        double var25;
        if (input[2] < 1902.6943) {
            if (input[2] < 276.9425) {
                if (input[2] < 234.36012) {
                    var25 = 0.009807841;
                } else {
                    var25 = 0.38533834;
                }
            } else {
                if (input[0] < 82.59636) {
                    var25 = -0.93335694;
                } else {
                    var25 = -0.08719619;
                }
            }
        } else {
            if (input[2] < 3490.1296) {
                if (input[0] < 72.47584) {
                    var25 = -0.19693121;
                } else {
                    var25 = 0.33750412;
                }
            } else {
                if (input[0] < 72.56652) {
                    var25 = 0.6354637;
                } else {
                    var25 = -0.087201215;
                }
            }
        }
        double var26;
        if (input[2] < 8230.291) {
            if (input[2] < 7093.6333) {
                if (input[2] < 5146.7627) {
                    var26 = -0.042002268;
                } else {
                    var26 = 0.17294887;
                }
            } else {
                if (input[0] < 75.17227) {
                    var26 = 0.007902908;
                } else {
                    var26 = -0.5323126;
                }
            }
        } else {
            if (input[0] < 80.0901) {
                var26 = 0.48231584;
            } else {
                var26 = 0.14397125;
            }
        }
        double var27;
        if (input[1] < 0.8323214) {
            if (input[0] < 72.56652) {
                var27 = 0.7010296;
            } else {
                if (input[0] < 75.059425) {
                    var27 = -0.33253342;
                } else {
                    var27 = 0.036513705;
                }
            }
        } else {
            if (input[0] < 72.41551) {
                if (input[0] < 70.12564) {
                    var27 = 0.23454463;
                } else {
                    var27 = 0.049533844;
                }
            } else {
                var27 = -0.61253184;
            }
        }
        double var28;
        if (input[2] < 8230.291) {
            if (input[2] < 7093.6333) {
                if (input[1] < 0.7656129) {
                    var28 = -0.054021735;
                } else {
                    var28 = 0.11985965;
                }
            } else {
                if (input[0] < 75.17227) {
                    var28 = 0.0056819916;
                } else {
                    var28 = -0.49925944;
                }
            }
        } else {
            if (input[0] < 80.0901) {
                var28 = 0.45637438;
            } else {
                var28 = 0.13043462;
            }
        }
        double var29;
        if (input[1] < 0.8323214) {
            if (input[0] < 72.56652) {
                var29 = 0.65998536;
            } else {
                if (input[1] < 0.80596554) {
                    var29 = 0.040194385;
                } else {
                    var29 = -0.26188168;
                }
            }
        } else {
            if (input[0] < 72.41551) {
                if (input[0] < 70.12564) {
                    var29 = 0.21091793;
                } else {
                    var29 = 0.041064452;
                }
            } else {
                var29 = -0.5755812;
            }
        }
        // ==========================================
        // BUG FIX: Replaced "nan" with the standard XGBoost base score of 0.5
        // ==========================================
        return 0.5 + (var0 + var1 + var2 + var3 + var4 + var5 + var6 + var7 + var8 + var9 + var10 + var11 + var12 + var13 + var14 + var15 + var16 + var17 + var18 + var19 + var20 + var21 + var22 + var23 + var24 + var25 + var26 + var27 + var28 + var29);
    }
}